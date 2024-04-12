(ns ie.simm.runtimes.notes
  "Active conversation mode that tries to extract factual and procedural knowledge from the user for later use in reasoning and recall.
   
   Properties: stateful, persistent, durable"
  (:require [ie.simm.languages.bindings :as lb]
            [ie.simm.languages.gen-ai :refer [cheap-llm reasoner-llm stt-basic image-gen]]
            [ie.simm.languages.web-search :refer [search]]
            [ie.simm.languages.browser :refer [extract-body]]
            [ie.simm.languages.chat :refer [send-text! send-photo! send-document!]]
            [ie.simm.prompts :as pr]
            [ie.simm.db :refer [ensure-conn conversation extract-tags msg->txs window-size]]
            [superv.async :refer [<?? go-try S go-loop-try <? >? put? go-for] :as sasync]
            [clojure.core.async :refer [chan pub sub mult tap timeout] :as async]
            [taoensso.timbre :refer [debug info warn error]]
            [datahike.api :as d]
            [hasch.core :refer [uuid]]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [etaoin.api :as e])
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
                note-titles (extract-tags summarization)
                _ (debug "=========================== CREATING NOTES ===============================")
                new-notes
                (<? S (async/into []
                                  (go-for S [[i note] (partition 2 (interleave (iterate inc 2) note-titles))
                                             :let [[nid body] (first (d/q '[:find ?n ?b :in $ ?t :where [?n :note/title ?t] [(get-else $ ?n :note/body "EMPTY") ?b]] db note))
                                                   prompt (format pr/note note body summarization #_conv)
                                                   new-body (<? S (reasoner-llm prompt))]
                                             :when (not (.contains new-body "SKIP"))
                                             :let [new-refs (extract-tags new-body)
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
            (debug "summarization tags" (extract-tags summarization))
            (d/transact conn (concat
                              [{:db/id -1
                                :conversation/summary summarization
                                :conversation/tag (extract-tags summarization)
                                :conversation/message messages}]
                              new-notes))
                              ;; keep exports up to date
            (doseq [[t b] (map (fn [{:keys [note/title note/body]}] [title body]) new-notes)]
              (debug "writing note" t)
              ;; write to org file in notes/chat-id/title.org
              (let [f (io/file (str "notes/" (:id chat) "/" t ".org"))]
                (io/make-parents f)
                (with-open [w (io/writer f)]
                  (binding [*out* w]
                    (println b)))))
            (extract-tags summarization))))

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

(defn notes
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
        po (pub pub-out :type)]
    ;; we will continuously interpret the messages
    (go-loop-try S [m (<? S msg-ch)]
                 (when m
                   (binding [lb/*chans* [next-in pi out po]]
                     (let [{:keys [msg]
                            {:keys [chat from]} :msg} m]
                       (try
                         (let [conn (ensure-conn peer (:id chat))

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


                           ;; 3. retrieve summaries for active tags
                               all-tags (d/q '[:find [?t ...] :where [_ :conversation/tag ?t]] @conn)
                               relevant (<? S (cheap-llm (format "You are given the following tags in brackets [[some tag]]:\n\n%s\n\nList the most relevant tags for the following conversation with descending priority.\n\n%s"
                                                                 (str/join ", " (map #(str "[[" % "]]") all-tags))
                                                                 conv)))
                               active-tags (concat (take 3 (extract-tags relevant)) [firstname])

                               summaries (d/q '[:find ?t ?s
                                                :in $ [?t ...]
                                                :where
                                                [?c :note/title ?t]
                                                [?c :note/body ?s]]
                                              @conn (concat active-tags (extract-tags conv)))
                               _ (debug "active tags" #_summaries active-tags)

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
                                   (let [issues (d/q '[:find [?t ...] :where [_ :note/title ?t]] @conn)]
                                     (d/transact conn (msg->txs (:result (<? S (send-text! (:id chat) (str "Notes:\n" (str/join "\n" (map #(format "* %s" %) issues))))))))))


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
                                         _ (d/transact conn (msg->txs (:result reply-msg)))]))]
                           )
                         (catch Exception e
                           (let [error-id (uuid)]
                             (error "Could not process message(" error-id "): " m e)
                             (<? S (send-text! (:id chat) (str "Sorry, I could not process your message. Error: " error-id))))))))
                   (recur (<? S msg-ch))))
    ;; Note that we pass through the supervisor, peer and new channels for composition
    [S peer [next-in prev-out]]))

