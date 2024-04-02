(ns ie.simm.runtimes.relational-assistance
  "Active conversation mode that tries to extract factual and procedural knowledge from the user for later use in reasoning and recall.
   
   Properties: stateful, persistent, durable"
  (:require [ie.simm.languages.bindings :as lb]
            [ie.simm.languages.gen-ai :refer [cheap-llm reasoner-llm stt-basic image-gen]]
            [ie.simm.languages.web-search :refer [search]]
            [ie.simm.languages.browser :refer [extract-body]]
            [ie.simm.languages.chat :refer [send-text! send-photo!]]
            [ie.simm.prompts :as pr]
            [superv.async :refer [<?? go-try S go-loop-try <? >? put?]]
            [clojure.core.async :refer [chan pub sub mult tap timeout]]
            [taoensso.timbre :refer [debug info warn error]]
            [datahike.api :as d]
            [hasch.core :refer [uuid]]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [etaoin.api :as e]))

(def default-schema (read-string (slurp "resources/schema.edn")))

(defn ensure-conn [peer chat-id]
  (or (get-in @peer [:conn chat-id])
      (let [;; ensure "databases directory exists and creating it if not"
            path (str "databases/" chat-id)
            _ (io/make-parents path)
            cfg {:store {:backend :file :scope "simm.ie/chats" :path path}}
            conn
            (try
              (let [cfg (d/create-database cfg)
                    conn (d/connect cfg)]
                (d/transact conn default-schema)
                conn)
              (catch Exception _
                (d/connect cfg)))]
        (d/transact conn default-schema)
        (swap! peer assoc-in [:conn chat-id] conn)
        conn)))

(defn conversation [conn chat-id window-size]
  (->>
   (d/q '[:find ?d ?f ?l ?n ?t
          :in $ ?chat
          :where
          [?c :chat/id ?chat]
          [?e :message/chat ?c]
          [?e :message/text ?t]
          [?e :message/date ?d]
          [?e :message/from ?u]
          [(get-else $ ?u :from/username "") ?n]
          [(get-else $ ?u :from/first_name "") ?f]
          [(get-else $ ?u :from/last_name "") ?l]]
        @conn chat-id)
   (sort-by first)
   (take-last window-size)
   (map (fn [[d f l n t]] (str d " " f " " l " (" n "): " t)))
   (str/join "\n")))

(defn extract-tags [text]
  (mapv second (re-seq #"\[\[([^\[\]]+)\]\]" text)))

(defn msg->txs [message]
  (let [{:keys [message_id from chat date text]} message
        tags (when text (extract-tags text))]
    [(let [{:keys [id is_bot first_name last_name username language_code]} from]
       (merge
        {:from/id (long id)
         :from/is_bot is_bot}
        (when username
          {:from/username username})
        (when language_code
          {:from/language_code language_code})
        (when first_name
          {:from/first_name first_name})
        (when last_name
          {:from/last_name last_name})))
     (let [{:keys [id first_name username type title all_members_are_administrators]} chat]
       (merge
        {:chat/id (long id)
         :chat/type type}
        (when username
          {:chat/username username})
        (when first_name
          {:chat/first_name first_name})
        (when title
          {:chat/title title})
        (when all_members_are_administrators
          {:chat/all_members_are_administrators all_members_are_administrators})))
     (merge
      {:message/id (long message_id)
       :message/from [:from/id (long (:id from))]
       :message/chat [:chat/id (long (:id chat))]
       :message/date (java.util.Date. (long (* 1000 date)))}
      (when text
        {:message/text text})
      (when (seq tags)
        {:message/tag tags}))]))


;; TODO factor into youtube middleware
;; require libpython

(require '[libpython-clj2.require :refer [require-python]] 
         '[libpython-clj2.python :refer [py. py.. py.-] :as py])

(require-python '[youtube_transcript_api :refer [YouTubeTranscriptApi]])

(defn youtube-transcript [video-id]
  ;; " ".join([t['text'] for t in transcript])
  (let [transcript (py. YouTubeTranscriptApi get_transcript video-id)]
    (str/join " " (map :text transcript))))

(comment
  (youtube-transcript "20TAkcy3aBY")

  )

(defn relational-assistance
  "This interpreter can derive facts and effects through a relational database."
  [[S peer [in out]]]
  ;; pass everything from in to next-in for the next middleware
  ;; and create publication channel for runtime context
  (let [mi (mult in)
        next-in (chan)
        _ (tap mi next-in)
        pub-in (chan)
        _ (tap mi pub-in)
        pi (pub pub-in :type)

       ;; subscriptions for this runtime context
        msg-ch (chan)
        _ (sub pi :ie.simm.runtimes.telegram/message msg-ch)

       ;; do the same in reverse for outputs from below
        prev-out (chan)
        mo (mult prev-out)
        _ (tap mo out)
        pub-out (chan)
        _ (tap mo pub-out)
        po (pub pub-out :type)

        active-tags (atom nil)]
    ;; we will continuously interpret the messages
    (go-loop-try S [m (<? S msg-ch)]
                 (when m
                   (binding [lb/*chans* [next-in pi out po]]
                     (let [{:keys [msg]
                            {:keys [text chat voice-path]} :msg} m]
                       (try
                         (let [_ (debug "received message" m)
                               start-time-ms (System/currentTimeMillis)
                               text (if-not voice-path text
                                            (let [transcript (<? S (stt-basic voice-path))]
                                              (when text (warn "Ignoring text in favor of voice message"))
                                              (debug "created transcript" transcript)
                                              (<? S (send-text! (:id chat) transcript))
                                              transcript))
                               text (when-let [;; if text matches http or https web URL extrect URL with regex
                                               url (re-find #"https?://\S+" text)]
                                      (if-let [;; extract youtube video id from URL
                                               youtube-id (second (re-find #"youtube.com/watch\?v=([^&]+)" url))]
                                        (try
                                          (let [transcript (youtube-transcript youtube-id)
                                                summary (<? S (cheap-llm (format pr/summarization-prompt transcript)))
                                                summary (str "Youtube transcript summary:\n" summary "\n" url)]
                                            (<? S (send-text! (:id chat) summary))
                                            summary)
                                          (catch Exception e
                                            (warn "Could not extract transcript from youtube video" youtube-id e)
                                            text))
                                        (try
                                          (let [body (<? S (extract-body url))
                                                summary (<? S (cheap-llm (format pr/summarization-prompt body)))]
                                            (<? S (send-text! (:id chat) summary))
                                            (str "Website summary:\n" summary "\n" url))
                                          (catch Exception e
                                            (warn "Could not extract body from URL" url e)
                                            text))))
                               msg (assoc msg :text text)

                               conn (ensure-conn peer (:id chat))
                               _ (debug "conn" conn)

                           ;; 1. add new facts from messages
                               _ (d/transact conn (msg->txs msg))
                               _ (debug "transacted")
                           ;; 2. recent conversation
                               window-size 20
                               conv (conversation conn (:id chat) window-size)
                               _ (debug "conversation" conv)
                               _ (when (zero? (mod (d/q '[:find (count ?m) .
                                                          :in $ ?cid
                                                          :where
                                                          [?m :message/chat ?c]
                                                          [?c :chat/id ?cid]]
                                                        @conn (:id chat))
                                                   window-size))
                                   (let [summarization  (<? S (reasoner-llm (format pr/summarization-prompt conv)))
                                         messages (->> (d/q '[:find ?d ?e
                                                              :in $ ?chat
                                                              :where
                                                              [?c :chat/id ?chat]
                                                              [?e :message/chat ?c]
                                                              [?e :message/date ?d]]
                                                            @conn (:id chat))
                                                       (sort-by first)
                                                       (take-last window-size)
                                                       (map second))]
                                     (debug "messages" messages)
                                     (debug "Summarization:" summarization)
                                     (<? S (send-text! (:id chat) (str "Note on recent conversation:\n" summarization)))
                                     (debug "with tags" (extract-tags summarization))
                                     (reset! active-tags (extract-tags summarization))
                                     (debug (d/transact conn [{:db/id -1
                                                               :conversation/summary summarization
                                                               :conversation/tag (extract-tags summarization)
                                                               :conversation/message messages}]))))

                           ;; 3. retrieve summaries for active tags
                               summaries (d/q '[:find [?s ...]
                                                :in $ [?t ...]
                                                :where
                                                [?c :conversation/tag ?t]
                                                [?c :conversation/summary ?s]]
                                              @conn (concat @active-tags (extract-tags conv)))
                               _ (debug "active summaries" summaries @active-tags)
                               _ (when (empty? @active-tags)
                                   (let [tags (d/q '[:find [?t ...] :where [_ :conversation/tag ?t]] @conn)
                                         relevant (<? S (cheap-llm (format "You are given the following tags in brackets [[some tag]]:\n\n%s\n\nList the most relevant tags for the following conversation.\n\n%s"
                                                                           (str/join ", " (map #(str "[[" % "]]") tags))
                                                                           conv)))]
                                     (debug "maybe relevant tags" relevant
                                            (reset! active-tags (extract-tags relevant)))))

                          ;; 2. derive reply
                               reply (<? S (cheap-llm (format pr/assistance-prompt (str/join "\n\n" summaries) conv)))
                               _ (debug "reply" reply)]
                           (when-not (or (.contains reply "WEBSEARCH") (.contains reply "QUIET"))
                             (when-let [prompt (second (re-find #"IMAGEGEN\('(.*)'\)" reply))]
                               (let [url (<? S (image-gen prompt))]
                                 (debug "generated image" url)
                                 (d/transact conn (msg->txs (:result (<? S (send-photo! (:id chat) url))))))))
                           (if (or (.contains reply "QUIET") (.contains reply "IMAGEGEN"))
                             (debug "No reply necessary")
                             (let [reply (if-let [terms (second (re-find #"WEBSEARCH\('(.*)'\)" reply))]
                                           (let [url (<? S (search terms))
                                                 body (<? S (extract-body url))
                                                 prompt (format pr/search-prompt terms body)
                                                 reply (<? S (cheap-llm prompt))]
                                             (debug "conducting web search" terms)
                                             (str reply "\n" url))
                                           reply)
                                   send-time-ms (System/currentTimeMillis)
                                   min-response-time 3000
                                   too-fast-by (max 0 (- min-response-time (- send-time-ms start-time-ms)))
                                   _ (debug "too fast by" too-fast-by)
                                   _ (<? S (timeout too-fast-by))
                                   reply-msg (<? S (send-text! (:id chat) reply))
                                   _ (debug "reply-msg" reply-msg)
                                   _ (d/transact conn (msg->txs (:result reply-msg)))])))
                         (catch Exception e
                           (let [error-id (uuid)]
                             (error "Could not process message(" error-id "): " m e)
                             (<? S (send-text! (:id chat) (str "Sorry, I could not process your message. Error: " error-id))))))))
                   (recur (<? S msg-ch))))
    ;; Note that we pass through the supervisor, peer and new channels for composition
    [S peer [next-in prev-out]]))


;; unportd logic from first prototype
(comment

 (h/defhandler bot-api
    (h/command-fn "start" (fn [{{id :id :as chat} :chat}]
                            (debug "Bot joined new chat: " chat)
                            (notify! id "Welcome! You can always get help with /help.")))

    (h/command-fn "help" (fn [{{id :id :as chat} :chat}]
                           (notify! id "konstruktiv is here to help!\n\nIt automatically transcribes audio messages to text. Try the following commands /daily, /assist, /summarize, /search, /imitate username.")))

    (h/command-fn "search" (fn [{:keys [text chat] {id :id} :chat}]
                             (let [terms (->> (str/split (str/trim text) #"\s+") rest (str/join " "))
                                   terms (if-not (empty? (str/trim terms))
                                           terms
                                           (openai/chat "gpt-3.5-turbo" (format "%s\n\n\nDerive a web search with a few relevant terms (words) from the previous conversation. Only return the terms and nothing else.\n" (conversation id))))]
                               (debug "searching for terms" terms)
                               (go
                                 (e/with-chrome-headless driver
                                   (notify! id (summarize-web-search driver terms)))))))

    (h/command-fn "add" (fn [{:keys [text chat] {id :id} :chat :as message}]
                          (try
                            (let [args (->> (str/split (str/trim text) #"\s+") rest)]
                              (case (first args)
                                "issue" (do (add-issue! conn (str/join " " (rest args)) id)
                                            (notify! id "Added issue." {:reply_to_message_id (:message_id message)})))
                              "Thanks!")
                            (catch Exception _
                              "Thanks!"))))

    (h/command-fn "remove" (fn [{:keys [text chat] {id :id} :chat :as message}]
                             (try
                               (let [args (->> (str/split (str/trim text) #"\s+") rest)]
                                 (case (first args)
                                   "issue" (do (remove-issue! conn (str/join " " (rest args)) id)
                                               (notify! id "Removed issue." {:reply_to_message_id (:message_id message)})))
                                 "Thanks!")
                               (catch Exception _
                                 "Thanks!"))))

    (h/command-fn "schedule" (fn [{:keys [text chat] {id :id} :chat :as message}]
                               (try
                                 (let [args (->> (str/split (str/trim text) #"\s+") rest)]
                                   (case (first args)
                                     "issue" (do (schedule-issue! conn (str/join " " (rest args)) id)
                                                 (notify! id "Scheduled issue at " {:reply_to_message_id (:message_id message)})))
                                   "Thanks!")
                                 (catch Exception _
                                   "Thanks!"))))

    (h/command-fn "list" (fn [{:keys [text chat] {id :id} :chat}]
                           (try
                             (let [args (->> (str/split (str/trim text) #"\s+") rest)]
                               (case (first args)
                                 "issue" (notify! id (list-issues conn id)))
                               "Thanks!")
                             (catch Exception _
                               "Thanks!"))))

    (h/command-fn "daily" (fn [{{id :id :as chat} :chat}]
                            (let [chat-chan (chan)]
                              (sub chat-pub id chat-chan)
                              (debug "daily exchange: " chat)
                              (daily-agenda chat chat-chan)
                            ;; TODO don't block thread!
                              #_((good-morning chat (m/watch !input))
                                 #(debug ::morning-success %)
                                 #(debug ::morning-failure %)))))

    (h/command-fn "assist"  (fn [{{id :id :as chat} :chat}]
                              (d/transact conn [[:db/add [:chat/id (long id)] :chat/under-assistance? true]])
                              (println "Help was requested in " chat)
                              (notify! id "Assistance is now turned on. You can pause it again with /pause.")))

    (h/command-fn "pause" (fn [{{id :id :as chat} :chat}]
                            (d/transact conn [[:db/add [:chat/id (long id)] :chat/under-assistance? false]])
                            (debug "Bot was paused in " chat)
                            (notify! id "Pausing for now. You can turn it on again with /assist.")))

    (h/command-fn "summarize" (fn [{{id :id :as chat} :chat}]
                                (let [summary (openai/chat "gpt-3.5-turbo" (format pr/summarization-prompt (conversation id)))]
                                  (debug "Generated summary: " summary)
                                  (notify! id
                                           #_{:reply_markup {:inline_keyboard [[{:text "Good" :callback_data "good"}
                                                                                {:text "Bad" :callback_data "bad"}]]}}
                                           summary))))

    (h/command-fn "imitate" (fn [{:keys [text] {id :id :as chat} :chat :as message}]
                              (let [username (->> (str/split (str/trim text) #"\s+")
                                                  second)
                                    _ (debug "Imitating " username)
                                    conversation (conversation id)
                                    _ (println conversation)
                                    prompt (format pr/clone-prompt username conversation)
                                    response (openai/chat "gpt-4-1106-preview" #_"gpt-3.5-turbo" prompt)]
                                (notify! id response))))

    (h/message-fn  (fn [{:keys [chat voice] :as message}]
                     (debug "Received message:" message)
                     (try
                       (let [chat-id (:id chat)
                             message (if-not voice
                                       message
                                       (let [transcript (create-transcript! voice)]
                                         (debug "created transcript" transcript)
                                         (notify! chat-id transcript {:reply_to_message_id (:message_id message)})
                                         (assoc message :text transcript)))]
                         (d/transact conn (msg->txs message))
                         #_(reset! !input message)
                         (put! in-chan message)
                         (when (get (d/entity @conn [:chat/id chat-id]) :chat/under-assistance?)
                           (assist! chat-id)))
                       (catch Exception e
                         (warn "Could not process message" message e)))))) 

 (defn daily-agenda [chat chat-chan]
  (go
    (let [{:keys [id username]} chat
          affects-agenda? #(let [res (openai/chat "gpt-4-1106-preview" #_"gpt-3.5-turbo" %)]
                             (debug "affects agenda" res)
                             (when-not (.contains res "NOOP") res))

          agenda (m/? (get-agenda username))

          _ (notify! id (format "Hey %s! How are you doing?" username))
          _reply-how-are-you-doing (<! chat-chan)
          ;; be polite
          _ (notify! id (openai/chat "gpt-3.5-turbo" (format "%s\n\nGiven this recent conversation, briefly reply to the last message directly." (conversation id))))

          ;; weather affects agenda
          conv (conversation id)
          _ (debug "conversation" conv)
          agenda-as-json (json/write-value-as-string (select-keys agenda #{:weather :agenda}))
          weather-affects-agenda? (affects-agenda? (format pr/weather-affects-agenda conv agenda-as-json username))
          _ (when weather-affects-agenda?
              (notify! id weather-affects-agenda?)
              (<! (go-loop [in (<! chat-chan)]
                    (let [conversation (conversation id)
                          _ (debug "proceeding in weather discussion" in)
                          not-done? (let [res (openai/chat "gpt-4-1106-preview" #_"gpt-3.5-turbo" (format "%s\n\nAre we done discussing how the weather affects the agenda in this conversation? Answer only with YES or NO." conversation))]
                                      (.contains res "NO"))]
                      (when not-done? 
                        (notify! id (openai/chat "gpt-4-1106-preview" (format "%s\n\nGiven the preceeding conversation, make a suggestion how to fix the agenda." conversation)))
                        (recur (<! chat-chan)))))))

          ;; emails affect agenda
          agenda-as-json (json/write-value-as-string (select-keys agenda #{:new-emails :agenda}))
          emails-affect-agenda? (affects-agenda? (format pr/emails-affect-agenda (conversation id) agenda-as-json username))

          _ (when emails-affect-agenda?
              (notify! id emails-affect-agenda?)
              (<! (go-loop [in (<! chat-chan)]
                    (let [conversation (conversation id)
                          _ (debug "proceeding in email discussion" in)
                          not-done? (let [res (openai/chat "gpt-4-1106-preview" #_"gpt-3.5-turbo" (format "%s\n\nAre we done discussing how the emails affect the agenda in this conversation? Answer only with YES or NO." conversation))]
                                      (.contains res "NO"))]
                      (when not-done? (recur (<! chat-chan)))))))]
      (notify! id "Thanks! That's all that there is discuss for now.")
      (close! chat-chan)))) 
  
  )