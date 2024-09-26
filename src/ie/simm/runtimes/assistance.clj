(ns ie.simm.runtimes.assistance
  "Active conversation mode that tries to extract factual and procedural knowledge from the user for later use in reasoning and recall.
   
   Properties: stateful, persistent, durable"
  (:require [ie.simm.languages.bindings :as lb]
            [ie.simm.languages.gen-ai :refer [cheap-llm cheap-llm stt-basic image-gen]]
            [ie.simm.languages.web-search :refer [search]]
            [ie.simm.languages.browser :refer [extract-body]]
            [ie.simm.languages.notes :refer [related-notes]]
            [ie.simm.languages.chat :refer [send-text! send-photo! send-document!]]
            [ie.simm.prompts :as pr]
            [ie.simm.db :refer [ensure-conn conversation extract-links msg->txs window-size]]
            [ie.simm.peer :as peer]
            [ie.simm.http :refer [response]]
            [ie.simm.website :refer [md-render default-chrome base-url]]
            [ie.simm.runtimes.notes :refer [zip-notes]] ;; TODO move
            [superv.async :refer [<?? go-try S go-loop-try <? >? put? go-for] :as sasync]
            [clojure.core.async :refer [chan pub sub mult tap timeout] :as async]
            [taoensso.timbre :refer [debug info warn error]]
            [datahike.api :as d]
            [hasch.core :refer [uuid]]
            [clojure.string :as str]))

(defn chat-overview [peer {{:keys [chat-id]} :path-params}]
  (let [conn (ensure-conn peer chat-id)
        title (or (:chat/title (d/entity @conn [:chat/id (Long/parseLong chat-id)])) "Noname chat")
        notes (->> (d/q '[:find ?t :where [?n :note/title ?t]] @conn)
                   (map first)
                   sort)]
    (response 
     (default-chrome title
      [:div {:class "container"}
       [:nav {:class "breadcrumb" :aria-label "breadcrumbs"}
        [:ul {}
         [:li [:span #_{:href "/#"} [:span {:class "icon is-small"} [:i {:class "bx bx-circle"}]] [:span "Systems"]]]
         [:li.is-active
          [:a {:href (str "/chats/" chat-id)}
           [:span {:class "icon is-small"} [:i {:class "bx bx-chat"}]]
           [:span title]]]]]
       [:div {:class "container"}
        [:h2 {:class "title is-2 is-spaced" :id "bots"}
         [:a {:class "" :href "bots"} [:i {:class "bx bx-bot"}]]
         [:span {:class ""} "Agents and Processes"]]
        [:div {:class "content"}
         [:p "Support agents are running in the background to assist you. They are able to answer questions, collaborate, provide summaries, and help you with your tasks. They are also able to learn from your interactions and improve over time. There are also simpler processes that can be triggered by certain keywords or commands. They are also called bots in some contexts."]]
        [:div {:class "container"}
         [:div.content
          [:h3 {:class "title is-3 is-spaced" :id "note-taker"}
           [:a {:class "" :href "note-taker"} [:i {:class "bx bx-bot"}]]
           [:span {:class ""} "Note-taker"]]
          [:p "The note-taker is a simple note-taking system that allows you to keep track of important information and conversations. You can create new notes, edit existing ones, and link them together. The note-taker will also automatically summarize conversations and create new notes for you."]
          [:div.container
           [:div.content
            [:h4 {:class "title is-4 is-spaced" :id "notes"}
             [:a {:class "" :href "notes"} "# "]
             [:span {:class ""} "Notes"]]
            [:p "These are all notes that have been created in this chat. You can click on a note to view its content, edit it, or delete it. You can also download all notes as a zip file."]
            [:a {:class "button" :href (str "/download/chat/" chat-id "/notes.zip")} [:span {:class "icon is-small"} [:i {:class "bx bx-download"}]] [:span "Download"]]
            [:div {:class "content"}
             (if (seq notes)
               [:ul (map (fn [f] [:li [:a {:href (str "/chats/" chat-id "/notes/" f)} f]]) notes)]
               "No notes.")]]]]]
        [:div.container
         [:div.content
          [:h3 {:class "title is-3 is-spaced" :id "issue-tracker"}
           [:a {:class "" :href "issue-tracker"} [:i {:class "bx bx-bot"}]]
           [:span {:class ""} "Issue tracker"]]
          [:div.content
           [:p "The issue tracker is a simple system that allows you to keep track of tasks, bugs, and other issues. you can create new issues, assign them to people, set priorities, and schedule them. the issue tracker will also automatically remind you of upcoming deadlines in the chat and help you to stay organized."]]
          [:div.container
           [:div.content
            [:h4 {:class "title is-4 is-spaced" :id "issues"}
             [:a {:class "" :href "issues"} "# "]
             [:span {:class ""} "Issues"]]
            (let [issues (->> (d/q '[:find ?t :where [?i :issue/title ?t]] @conn)
                              (map first)
                              sort)]
              (if (seq issues)
                [:ul
                 (map (fn [f] [:li [:a {:href (str "/chats/" chat-id "/issues/" f)} f]]) issues)]
                "No issues."))]]]]]]))))

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

        ;; TODO figure out prefix, here conflict if chats/
        routes [["/chats/:chat-id" {:get (partial #'chat-overview peer)}]]]
    (peer/add-routes! peer :assistance routes)
    ;; we will continuously interpret the messages
    (binding [lb/*chans* [next-in pi out po]]
      (go-loop-try S [m (<? S msg-ch)]
                   (when m
                     (let [{:keys [msg]
                            {:keys [chat from text]} :msg} m]
                       (try
                         (let [_ (debug "received message" m)
                               start-time-ms (System/currentTimeMillis)

                               conn (ensure-conn peer (:id chat))
                               _ (debug "conn" conn)

                           ;; 1. add new facts from messages
                               _ (d/transact conn (msg->txs msg))
                               _ (debug "transacted")
                           ;; 2. recent conversation
                               conv (conversation @conn (:id chat) window-size)
                               _ (debug "conversation" conv)
                               msg-count (d/q '[:find (count ?m) .
                                                :in $ ?cid
                                                :where
                                                [?m :message/chat ?c]
                                                [?c :chat/id ?cid]]
                                              @conn (:id chat))
                               _ (debug "message count" msg-count)]
                           (when (or (= (:type chat) "private")
                                     (.contains text "@simmie"))
                             (let [;; 3. retrieve summaries for active links
                                   {:keys [active-links summaries]} (<? S (related-notes conn conv))
                                   _ (debug "active links" #_summaries active-links)

                          ;; 4. derive reply
                                   assist-prompt (format pr/assistance
                                                         (str/join "\n\n" (map (fn [[t s]] (format "Title: %s\nBody: %s" t s))
                                                                               summaries))
                                                         conv
                                                         (str (java.util.Date.)))
                                   _ (debug "prompt" assist-prompt)
                                   reply (<? S (cheap-llm assist-prompt))
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
                                       (<? S (send-document! (:id chat) (zip-notes (:id chat))))
                                       (d/transact conn (msg->txs (:result (<? S (send-text! (:id chat) "Done."))))))

                                   _ (when-let [title (second (re-find #"RETRIEVE_NOTE\(['\"](.*)['\"]\)" reply))]
                                       (if-let [body (d/q '[:find ?b . :in $ ?t :where [?n :note/title ?t] [?n :note/body ?b]] @conn title)]
                                         (do
                                           (debug "retrieved note" title body)
                                           (d/transact conn (msg->txs (:result (<? S (send-text! (:id chat) (str "Retrieved " title "\n" body)))))))
                                         (d/transact conn (msg->txs (:result (<? S (send-text! (:id chat) (str "Could not find note: " title))))))))

                                   _ (when (.contains reply "CHAT_HOMEPAGE")
                                       (debug "chat homepage")
                                       (let [#_#_issues (d/q '[:find [?t ...] :where [_ :note/title ?t]] @conn)]
                                         (d/transact conn (msg->txs (:result (<? S (send-text! (:id chat) (str (format "%s/chats/%s" base-url (:id chat))))))))))


                                   _ (if (or (.contains reply "QUIET") (.contains reply "IMAGEGEN") (.contains reply "LIST_ISSUES") (.contains reply "ADD_ISSUE")
                                             (.contains reply "REMOVE_ISSUE") (.contains reply "SEND_NOTES") (.contains reply "RETRIEVE_NOTE") (.contains reply "CHAT_HOMEPAGE"))
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

                                             reply (if (<= msg-count 1)
                                                     (format "Hello! I am Simmie and am here to help you with your tasks or just to have fun. You can ask me for assistance at any time. I can also summarize conversations and help you with your notes. I can also track issues, do web searches, summarize links you send to me or generate images. Check out the website for this chat at %s/chats/%s" base-url (:id chat))
                                                     reply)
                                             reply-msg (<? S (send-text! (:id chat) reply))
                                             _ (debug "reply-msg" reply-msg)
                                             _ (d/transact conn (msg->txs (:result reply-msg)))]))])))
                         (catch Exception e
                           (let [error-id (uuid)]
                             (error "Could not process message(" error-id "): " m e)
                             (<? S (send-text! (:id chat) (str "Sorry, I could not process your message. Error: " error-id))))))))
                   (recur (<? S msg-ch))))
    ;; Note that we pass through the supervisor, peer and new channels for composition
    [S peer [next-in prev-out]]))
