(ns is.simm.runtimes.notes
  "Active conversation mode that tries to extract factual and procedural knowledge from the user for later use in reasoning and recall.
   
   Properties: stateful, persistent, durable"
  (:require [is.simm.languages.bindings :as lb]
            [is.simm.languages.gen-ai :refer [cheap-llm cheap-llm]]
            [is.simm.prompts :as pr]
            [is.simm.db :refer [ensure-conn conversation extract-links msg->txs window-size]]
            [is.simm.peer :as peer]
            [is.simm.website :refer [md-render default-chrome base-url]]
            [is.simm.http :refer [response]]
            [is.simm.config :refer [config]]
            [superv.async :refer [<?? go-try S go-loop-try <? >? put? go-for] :as sasync]
            [clojure.core.async :refer [chan pub sub mult tap timeout] :as async]
            [taoensso.timbre :refer [debug info warn error]]
            [datahike.api :as d]
            [clojure.string :as str]
            [clojure.java.io :as io])
  (:import [java.util.zip ZipEntry ZipOutputStream]))


(def ^:const summarization-interval (:summarization-interval config))

(defn summarize [S peer new-msg-chan]
  (go-loop-try S [{:keys [msg] {:keys [chat from text]} :msg} (<? S new-msg-chan)]
               (when msg
                 ;; poll from channel until empty
                 (while (async/poll! new-msg-chan))
                 (debug "=========================== SUMMARIZING ===============================")
                 (let [conn (ensure-conn peer (:id chat))
                       conv (conversation @conn (:id chat) window-size)
                       db @conn
                       summarization  (<? S (cheap-llm (format pr/summarization conv)))
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
                                                          prompt (format pr/note note body #_summarization conv)
                                                          new-body (<? S (cheap-llm prompt))]
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
                   (<? S (d/transact! conn (concat
                                           [{:db/id -1
                                             :conversation/summary summarization
                                             :conversation/date (java.util.Date.)
                                             :conversation/message messages}]
                                           new-notes)))
                              ;; keep exports up to date
                   (doseq [[t b] (map (fn [{:keys [note/title note/body]}] [title body]) new-notes)]
                     (debug "writing note" t)
                     (let [f (io/file (str "chats/" (:id chat) "/" t ".md"))]
                       (io/make-parents f)
                       (with-open [w (io/writer f)]
                         (binding [*out* w]
                           (println b)))))
                   (extract-links summarization))
                 (<? S (timeout summarization-interval))
                 (recur (<? S new-msg-chan)))))

(defn zip-notes [chat-id]
  (let [zip-file (io/file (str "chats/" chat-id ".zip"))
        zip-out (io/output-stream zip-file)
        zip (ZipOutputStream. zip-out)
        notes-dir (io/file (str "chats/" chat-id))
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

(defn view-note [peer {{:keys [chat-id note]} :path-params}]
  (let [conn (ensure-conn peer chat-id)
        body (:note/body (d/entity @conn [:note/title note]))
        chat-title (:chat/title (d/entity @conn [:chat/id (Long/parseLong chat-id)]))
        summaries (->>
                   (d/q '[:find ?s ?d ?t ?n ?f ?l
                          :in $ ?note
                          :where
                          [?n :note/title ?note]
                          [?n :note/summary ?c]
                          [?c :conversation/summary ?s]
                          [?c :conversation/message ?m]
                          [?m :message/date ?d]
                          [?m :message/text ?t]
                          [?m :message/from ?u]
                          [(get-else $ ?u :from/username "") ?n]
                          [(get-else $ ?u :from/first_name "") ?f]
                          [(get-else $ ?u :from/last_name "") ?l]]
                        @conn note)
                   (reduce (fn [m [s d t n f l]]
                             (update m s (fnil conj []) [d t n f l]))
                           {}))
        linking-notes (d/q '[:find ?lt
                             :in $ ?t
                             :where
                             [?n :note/title ?t]
                             [?l :note/link ?n]
                             [?l :note/title ?lt]]
                           @conn note)]
    (response
     (default-chrome note
      [:div {:class "container"}
       [:nav {:class "breadcrumb" :aria-label "breadcrumbs"}
        [:ul {} #_[:li [:p "Process"]]
         [:li [:span #_{:href "/#"} [:span {:class "icon is-small"} [:i {:class "bx bx-circle"}]] [:span "Systems"]]]
         [:li [:a {:href (str "/chats/" chat-id)} [:span {:class "icon is-small"} [:i {:class "bx bx-chat"}]] [:span (or chat-title "Noname chat")]]]
         [:li.is-active [:a {:href (str "/chats/" chat-id "/notes/" note)} [:span {:class "icon is-small"} [:i {:class "bx bx-note"}]] [:span note]]]]]
       [:article {:id "note" :class "message"}
        [:div {:class "message-header"}
         [:p note]
         [:button {:class "delete" :hx-post (str "/chats/" chat-id "/notes/" note "/delete") :hx-trigger "click" :hx-target "#note" :hx-confirm "Are you sure you want to delete this note?" :hx-swap "outerHTML"}]]
        [:div {:class "message-body content"}
         (if body (md-render body) "Note does not exist yet.")
         [:button {:class "button is-primary" :hx-post (str "/chats/" chat-id "/notes/" note "/edit") :hx-target "#note" :hx-trigger "click" :hx-swap "outerHTML"} "Edit"]]]
       (when (seq linking-notes)
         [:div {:class "content"}
          [:h4 {:class "title is-4 is-spaced" :id "incoming-pointers"}
           [:a {:class "" :href "incoming-pointers"} "# "]
           [:span {:class ""} "Pointing to this note"]]
          (for [[ln] linking-notes]
            [:div {:class "content"}
             [:a {:href (str "/chats/" chat-id "/notes/" ln)} ln]])])
       (when (seq summaries)
         [:div {:class "content"}
          (for [[i [s ds]] (map (fn [i s] [i s]) (rest (range)) summaries)]
            [:div {:class "content"}
             [:div.content
              [:h4 {:class "title is-4 is-spaced" :id (str i "-source")}
               [:a {:class "" :href (str "#" i "-source")} "# "]
               [:span {:class ""} (str i ". Conversation")]]
              (md-render s)]
             [:div.content
              [:h5 {:class "title is-5 is-spaced" :id (str i "-message-history")}
               [:a {:class "" :href (str "#" i "-message-history")} "# "]
               [:span {:class ""}  "Messages"]]
              [:ul (for [[d t n f l] (sort-by first ds)]
                     [:li [:div {:class "content"}
                           [:h6 (md-render (str "Message from [[" f " " l "]] (" n ") on " d))]
                           (md-render t)]])]]])])]))))

(defn edit-note [peer {{:keys [chat-id note]} :path-params}]
  (let [conn (ensure-conn peer chat-id)
        body (:note/body (d/entity @conn [:note/title note]))
        edit? (get-in (swap! peer update-in [:transient chat-id note :edit] not) [:transient chat-id note :edit] false)]
    (response
     [:article {:id "note" :class "message"}
      [:div {:class "message-header"}
       [:p note]
       [:button {:class "delete" :hx-post (str "/chats/" chat-id "/notes/" note "/delete") :hx-trigger "click" :hx-target "#note" :hx-swap "outerHTML" :hx-confirm "Are you sure you want to delete this note?"}]]
      [:div {:class "message-body content"}
       (if edit?
         [:textarea {:class "textarea" :rows 20 :name "note" :hx-post (str "/chats/" chat-id "/notes/" note "/edited") :hx-trigger "keyup changed delay:500ms"} body]
         (if body (md-render body) "Note does not exist yet."))
       [:button {:class "button is-primary" :hx-post (str "/chats/" chat-id "/notes/" note "/edit") :hx-target "#note" :hx-swap "outerHTML" :hx-trigger "click"} "Edit"]]])))

(defn edited-note [peer {{:keys [chat-id note]} :path-params
                         :keys [params]}]
  (let [conn (ensure-conn peer chat-id)
        id (or (:db/id (d/entity @conn [:note/title note])) (d/tempid :db.part/user))
        new-body (get params "note")
        links (extract-links new-body)]
    (d/transact conn [(merge
                       {:db/id id
                        :note/title note
                        :note/body new-body}
                       (when (seq links)
                         {:note/link (mapv first (d/q '[:find ?n :in $ [?t ...] :where [?n :note/title ?t]] @conn links))}))])
    {:status 200
     :body "Success."}))

(defn delete-note [peer {{:keys [chat-id note]} :path-params
                         :keys [params]}]
  (let [conn (ensure-conn peer chat-id)]
    (debug "Deleted note entity" (d/transact conn [[:db/retractEntity [:note/title note]]]))
    (response [:div {:id "note" :class "notification"}
               "Note does not exist yet."])))

(defn notes
  "This interpreter takes notes and exposes the most related notes to the system."
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
        _ (sub pi :is.simm.runtimes.telegram/message msg-ch)

        related-ch (chan 1000)
        _ (sub pi :is.simm.languages.notes/related-notes related-ch)

       ;; do the same in reverse for outputs from below
        prev-out (chan)
        mo (mult prev-out)
        _ (tap mo out)
        pub-out (chan)
        _ (tap mo pub-out)
        po (pub pub-out :type)

        ;; TODO figure out prefix, here conflict if chats/
        routes [["/download/chat/:chat-id/notes.zip" {:get (fn [{{:keys [chat-id]} :path-params}] {:status 200 :body (zip-notes chat-id)})}]
                ["/chats/:chat-id/notes/:note" {:get (partial #'view-note peer)}]
                ["/chats/:chat-id/notes/:note/edit" {:post (partial #'edit-note peer)}]
                ["/chats/:chat-id/notes/:note/edited" {:post (partial #'edited-note peer)}]
                ["/chats/:chat-id/notes/:note/delete" {:post (partial #'delete-note peer)}]]]
    (peer/add-routes! peer :notes routes)
    ;; we will continuously interpret the messages
    (binding [lb/*chans* [next-in pi out po]]
      (summarize S peer msg-ch)

      (go-loop-try S [{[conn conv] :args :as m} (<? S related-ch)]
                   (debug "related notes request" m)
                   (when m
                     (let [all-links (d/q '[:find [?t ...] :where [_ :conversation/link ?t]] @conn)
                           related (<? S (cheap-llm (format "You are given the following links in Wikipedia style brackets [[some entity]]:\n\n%s\n\nList the most related links for the following conversation with descending priority.\n\n%s"
                                                            (str/join ", " (map #(str "[[" % "]]") all-links))
                                                            conv)))
                           active-links (vec (take 3 (extract-links related)))

                           summaries (d/q '[:find ?t ?s
                                            :in $ [?t ...]
                                            :where
                                            [?c :note/title ?t]
                                            [?c :note/body ?s]]
                                          @conn (concat active-links (extract-links conv)))
                           _ (debug "related notes reply" active-links summaries)]
                       (put? S out (assoc m
                                          :type :is.simm.languages.notes/related-notes-reply
                                          :response {:active-links active-links
                                                     :summaries summaries})))
                     (recur (<? S related-ch)))))
    ;; Note that we pass through the supervisor, peer and new channels for composition
    [S peer [next-in prev-out]]))
