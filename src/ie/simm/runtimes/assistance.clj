(ns ie.simm.runtimes.assistance
  "Active conversation mode that tries to extract factual and procedural knowledge from the user for later use in reasoning and recall.
   
   Properties: stateful, persistent, durable"
  (:require [ie.simm.languages.bindings :as lb]
            [ie.simm.languages.gen-ai :refer [cheap-llm reasoner-llm stt-basic image-gen]]
            [ie.simm.languages.web-search :refer [search]]
            [ie.simm.languages.browser :refer [extract-body]]
            [ie.simm.languages.chat :refer [send-text! send-photo! send-document!]]
            [ie.simm.prompts :as pr]
            [ie.simm.db :refer [ensure-conn conversation extract-links msg->txs window-size]]
            [superv.async :refer [<?? go-try S go-loop-try <? >? put? go-for] :as sasync]
            [clojure.core.async :refer [chan pub sub mult tap timeout] :as async]
            [taoensso.timbre :refer [debug info warn error]]
            [datahike.api :as d]
            [hasch.core :refer [uuid]]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [hiccup.core :as h]
            [nextjournal.markdown :as md]
            [nextjournal.markdown.transform :as md.transform]
            [nextjournal.markdown.parser :as md.parser])
  (:import [java.util.zip ZipEntry ZipOutputStream]))

(defn summarize [S conn conv chat]
  (go-try S
          (debug "=========================== SUMMARIZING ===============================")
          (let [db @conn
                summarization  (<? S (reasoner-llm (format pr/summarization conv)))
                messages (->> (d/q '[:find ?d ?e
                                     :in $ ?chat
                                     :where
                                     [?c :chat/id ?chat]
                                     [?e :message/chat ?c]
                                     [?e :message/date ?d]]
                                   db (:id chat))
                              (sort-by first)
                              (take-last window-size)
                              (map second))
                note-titles (extract-links summarization)
                _ (debug "=========================== CREATING NOTES ===============================")
                new-notes
                (<? S (async/into []
                                  (go-for S [[i note] (partition 2 (interleave (iterate inc 2) note-titles))
                                             :let [[nid body] (first (d/q '[:find ?n ?b :in $ ?t :where [?n :note/title ?t] [(get-else $ ?n :note/body "EMPTY") ?b]] db note))
                                                   prompt (format pr/note note body summarization #_conv)
                                                   new-body (<? S (reasoner-llm prompt))]
                                             :when (not (.contains new-body "SKIP"))
                                             :let [new-refs (extract-links new-body)
                                                   ref-ids (mapv first (d/q '[:find ?n
                                                                              :in $ [?t ...]
                                                                              :where
                                                                              [?n :note/title ?t]]
                                                                            db new-refs))]]
                                          {:db/id (or nid (- i))
                                           :note/title note
                                           :note/body new-body
                                           :note/link ref-ids
                                           :note/summary -1})))]
            (debug "=========================== STORING NOTES ===============================")
            (debug "Summarization:" summarization)
            (debug "summarization links" (extract-links summarization))
            (d/transact conn (concat
                              [{:db/id -1
                                :conversation/summary summarization
                                :conversation/link (extract-links summarization)
                                :conversation/message messages}]
                              new-notes))
                              ;; keep exports up to date
            (doseq [[t b] (map (fn [{:keys [note/title note/body]}] [title body]) new-notes)]
              (debug "writing note" t)
              ;; write to org file in notes/chat-id/title.org
              (let [f (io/file (str "notes/" (:id chat) "/" t ".md"))]
                (io/make-parents f)
                (with-open [w (io/writer f)]
                  (binding [*out* w]
                    (println b)))))
            (extract-links summarization))))

(defn zip-notes [chat-id]
  (let [zip-file (io/file (str "notes/" chat-id ".zip"))
        zip-out (io/output-stream zip-file)
        zip (ZipOutputStream. zip-out)
        notes-dir (io/file (str "notes/" chat-id))
        _ (io/make-parents notes-dir)
        _ (io/make-parents zip-file)
        _ (doseq [f (rest (file-seq notes-dir))]
            (let [entry (ZipEntry. (str chat-id "/" (.getName f)))]
              (.putNextEntry zip entry)
              (with-open [in (io/input-stream f)]
                (io/copy in zip))
              (.closeEntry zip)))]
    (.close zip)
    (.close zip-out)
    zip-file))


(def base-url "https://ec2-34-218-223-7.us-west-2.compute.amazonaws.com")



(defn assistance
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
        msg-ch (chan 1000)
        _ (sub pi :ie.simm.runtimes.telegram/message msg-ch)

       ;; do the same in reverse for outputs from below
        prev-out (chan)
        mo (mult prev-out)
        _ (tap mo out)
        pub-out (chan)
        _ (tap mo pub-out)
        po (pub pub-out :type)

        ;; TODO figure out prefix, here conflict if notes/
        routes [["/download/chat/:chat-id/notes.zip"
                 {:get (fn [{{:keys [chat-id]} :path-params}]
                         {:status 200 :body (zip-notes chat-id)})}]
                ["/notes/:chat-id"
                 {:get (fn [{{:keys [chat-id]} :path-params}]
                         ;; list the notes in basic HTML
                         (let [conn (ensure-conn peer chat-id)]
                           {:status 200
                            :body (h/html
                                   [:html
                                    [:head
                                     [:meta {:charset "utf-8"}]
                                     [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
                                     [:link {:href "https://cdn.jsdelivr.net/npm/katex@0.13.13/dist/katex.min.css" :rel "stylesheet" :type "text/css"}]
                                     [:link {:href "https://fonts.bunny.net" :rel "preconnect"}]
                                     [:link {:href "https://fonts.bunny.net/css?family=fira-mono:400,700%7Cfira-sans:400,400i,500,500i,700,700i%7Cfira-sans-condensed:700,700i%7Cpt-serif:400,400i,700,700i" :rel "stylesheet" :type "text/css"}]
                                     [:title "Notes"]
                                     [:link {:rel "stylesheet" :href "https://cdnjs.cloudflare.com/ajax/libs/bulma/0.9.3/css/bulma.min.css"}]]
                                    [:body
                                     [:div {:class "flex"}
                                      [:h1 "Notes"]
                                      [:a {:href (str "/download/chat/" chat-id "/notes.zip")} "Download"]
                                      [:ul (map (fn [[f]] [:li [:a {:href (str "/notes/" chat-id "/" f)} f]])
                                                (d/q '[:find ?t :where [?n :note/title ?t]] @conn))]]]])}))}]
                ;; access each individual node link as referenced above
                ["/notes/:chat-id/:note"
                 {:get (fn [{{:keys [chat-id note]} :path-params}]
                         (let [conn (ensure-conn peer chat-id)
                               body (:note/body (d/entity @conn [:note/title note]))]
                           {:status 200
                            :body
                            (h/html
                             [:html
                              [:head
                               [:meta {:charset "utf-8"}]
                               [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
                               [:link {:href "https://cdn.jsdelivr.net/npm/katex@0.13.13/dist/katex.min.css" :rel "stylesheet" :type "text/css"}]
                               [:link {:href "https://fonts.bunny.net" :rel "preconnect"}]
                               [:link {:href "https://fonts.bunny.net/css?family=fira-mono:400,700%7Cfira-sans:400,400i,500,500i,700,700i%7Cfira-sans-condensed:700,700i%7Cpt-serif:400,400i,700,700i" :rel "stylesheet" :type "text/css"}]
                               [:title note]
                               [:link {:rel "stylesheet" :href "https://cdnjs.cloudflare.com/ajax/libs/bulma/0.9.3/css/bulma.min.css"}]]
                              [:body
                               [:div {:class "flex"}
                                [:h1 note]
                                (if (string? body)
                                  (md.transform/->hiccup (md/parse (update md.parser/empty-doc :text-tokenizers concat [md.parser/internal-link-tokenizer md.parser/hashtag-tokenizer])
                                                                   body))
                                  "Note does not exist yet.")]]])}))}]]]
    (swap! peer assoc-in [:http :routes :assistance] routes)
    ;; we will continuously interpret the messages
    (go-loop-try S [m (<? S msg-ch)]
                 (when m
                   (binding [lb/*chans* [next-in pi out po]]
                     (let [{:keys [msg]
                            {:keys [chat from]} :msg} m]
                       (try
                         (let [_ (debug "received message" m)
                               firstname (:first_name from)
                               start-time-ms (System/currentTimeMillis)

                               conn (ensure-conn peer (:id chat))
                               _ (debug "conn" conn)

                           ;; 1. add new facts from messages
                               _ (d/transact conn (msg->txs msg))
                               _ (debug "transacted")
                           ;; 2. recent conversation
                               conv (conversation @conn (:id chat) window-size)
                               _ (debug "conversation" conv)
                               _ (debug "message count" (d/q '[:find (count ?m) .
                                                               :in $ ?cid
                                                               :where
                                                               [?m :message/chat ?c]
                                                               [?c :chat/id ?cid]]
                                                             @conn (:id chat)))
                               _ (when (<= (mod (d/q '[:find (count ?m) .
                                                       :in $ ?cid
                                                       :where
                                                       [?m :message/chat ?c]
                                                       [?c :chat/id ?cid]]
                                                     @conn (:id chat))
                                                window-size) 1)
                                   (summarize S conn conv chat))


                           ;; 3. retrieve summaries for active links
                               all-links (d/q '[:find [?t ...] :where [_ :conversation/link ?t]] @conn)
                               relevant (<? S (cheap-llm (format "You are given the following links in Wikipedia style brackets [[some entity]]:\n\n%s\n\nList the most relevant links for the following conversation with descending priority.\n\n%s"
                                                                 (str/join ", " (map #(str "[[" % "]]") all-links))
                                                                 conv)))
                               active-links (concat (take 3 (extract-links relevant)) [firstname])

                               summaries (d/q '[:find ?t ?s
                                                :in $ [?t ...]
                                                :where
                                                [?c :note/title ?t]
                                                [?c :note/body ?s]]
                                              @conn (concat active-links (extract-links conv)))
                               _ (debug "active links" #_summaries active-links)

                          ;; 4. derive reply
                               assist-prompt (format pr/assistance
                                                     (str/join "\n\n" (map (fn [[t s]] (format "Title: %s\nBody: %s" t s))
                                                                           summaries))
                                                     conv
                                                     (str (java.util.Date.)))
                               _ (debug "prompt" assist-prompt)
                               reply (<? S (reasoner-llm assist-prompt))
                               _ (debug "reply" reply)

                               ;; 5. dispatch
                               _ (when-let [prompt (second (re-find #"IMAGEGEN\(['\"](.*)['\"]\)" reply))]
                                   (let [url (<? S (image-gen prompt))]
                                     (debug "generated image" url)
                                     (d/transact conn (msg->txs (:result (<? S (send-photo! (:id chat) url)))))))

                               _ (when-let [title (second (re-find #"ADD_ISSUE\(['\"](.*)['\"]\)" reply))]
                                   (debug "adding issue" title)
                                   (d/transact conn [{:db/id -1 :issue/title title}])
                                   (d/transact conn (msg->txs (:result (<? S (send-text! (:id chat) (str "Added issue: " title)))))))

                               _ (when-let [title (second (re-find #"REMOVE_ISSUE\(['\"](.*)['\"]\)" reply))]
                                   (if-let [eid (d/q '[:find ?i . :in $ ?t :where [?i :issue/title ?t]] @conn title)]
                                     (do
                                       (debug "removing issue" title eid)
                                       (d/transact conn [[:db/retractEntity eid]])
                                       (d/transact conn (msg->txs (:result (<? S (send-text! (:id chat) (str "Removed issue: " title)))))))
                                     (d/transact conn (msg->txs (:result (<? S (send-text! (:id chat) (str "Could not find issue: " title))))))))

                               _ (when (or (.contains reply "LIST_ISSUES") (.contains reply "DAILY"))
                                   (debug "listing issues")
                                   (let [issues (d/q '[:find [?t ...] :where [_ :issue/title ?t]] @conn)]
                                     (d/transact conn (msg->txs (:result (<? S (send-text! (:id chat) (str "Issues:\n" (str/join "\n" (map #(format "* %s" %) issues))))))))))

                               _ (when (.contains reply "SEND_NOTES")
                               ;; TODO transact document info
                                   (<? S (send-document! (:id chat) (zip-notes (:id chat))))
                                   (d/transact conn (msg->txs (:result (<? S (send-text! (:id chat) "Done."))))))

                               _ (when-let [title (second (re-find #"RETRIEVE_NOTE\(['\"](.*)['\"]\)" reply))]
                                   (if-let [body (d/q '[:find ?b . :in $ ?t :where [?n :note/title ?t] [?n :note/body ?b]] @conn title)]
                                     (do
                                       (debug "retrieved note" title body)
                                       (d/transact conn (msg->txs (:result (<? S (send-text! (:id chat) (str "Retrieved " title "\n" body)))))))
                                     (d/transact conn (msg->txs (:result (<? S (send-text! (:id chat) (str "Could not find note: " title))))))))

                               _ (when (.contains reply "LIST_NOTES")
                                   (debug "listing notes")
                                   (let [#_#_issues (d/q '[:find [?t ...] :where [_ :note/title ?t]] @conn)]
                                     (d/transact conn (msg->txs (:result (<? S (send-text! (:id chat) (str "Notes:\n" (format "%s/notes/%s" base-url (:id chat)) #_(str/join "\n" (map #(format "* %s" %) issues))))))))))


                               _ (if (or (.contains reply "QUIET") (.contains reply "IMAGEGEN") (.contains reply "LIST_ISSUES") (.contains reply "ADD_ISSUE")
                                         (.contains reply "REMOVE_ISSUE") (.contains reply "SEND_NOTES") (.contains reply "RETRIEVE_NOTE") (.contains reply "LIST_NOTES"))
                                   (debug "No reply necessary")
                                   (let [reply (if-let [terms (second (re-find #"WEBSEARCH\(['\"](.*)['\"]\)" reply))]
                                                 (let [url (<? S (search terms))
                                                       body (<? S (extract-body url))
                                                       prompt (format pr/search (conversation @conn (:id chat) 5) body)
                                                       reply (<? S (cheap-llm prompt))]
                                                   (debug "conducting web search" terms)
                                                   (str reply "\n" url))
                                                 (.replace reply "DAILY" ""))
                                         send-time-ms (System/currentTimeMillis)
                                         min-response-time 3000
                                         too-fast-by (max 0 (- min-response-time (- send-time-ms start-time-ms)))
                                         _ (debug "too fast by" too-fast-by)
                                         _ (<? S (timeout too-fast-by))
                                         reply-msg (<? S (send-text! (:id chat) reply))
                                         _ (debug "reply-msg" reply-msg)
                                         _ (d/transact conn (msg->txs (:result reply-msg)))]))])
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
                                (let [summary (openai/chat "gpt-3.5-turbo" (format pr/summarization (conversation id)))]
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