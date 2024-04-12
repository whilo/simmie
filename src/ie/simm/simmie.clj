(ns ie.simm.simmie
  (:require [taoensso.timbre :as log]
            [taoensso.timbre.appenders.core :as appenders]
            [superv.async :refer [S go-try <? <??] :as sasync]
            [ie.simm.http :refer [ring-handler]]
            [ring.adapter.jetty :refer [run-jetty]]
            [clojure.core.async :refer [chan close!]]
            [ie.simm.towers :refer [default debug test-tower]]
            [nrepl.server :refer [start-server stop-server]])
  (:gen-class))

(defonce nrepl-server (start-server :port 37888))

(log/merge-config!
  {:appenders {:spit (appenders/spit-appender {:fname "server.log"})}})

(log/set-min-level! :debug)

(defn -main
  "Starting a peer server."
  [& _args]
  (let [in (chan)
        out (chan)]
    (def chans [in out])
    (def peer (atom {}))
    (sasync/restarting-supervisor (fn [S] (go-try S 
                                                  ((debug) [S peer chans])
                                                  (let [ring (ring-handler (get-in @peer [:http :routes]))
                                                        server (run-jetty ring {:port 8080 :join? false})]
                                                    (swap! peer assoc-in [:http :server] server)
                                                    (log/info "Server started.")
                                                    ;; this only unblocks on crash
                                                    (<? S (chan))
                                                    (.stop server))))
                                  :delay (* 10 1000)
                                  :log-fn (fn [level msg] (log/log level msg)))
    (log/info "Server started.")
    ;; HACK to block
    (<?? S (chan))))

(comment

  (close! (first chans))

  ;; pull above let into top level defs
  
  (def in (chan))

  (def out (chan))

  (def peer (atom {}))

  (def tower ((debug) #_(test-tower) [S peer [in out]]))

  (close! in)

  (require '[datahike.api :as d])

  (require '[ie.simm.runtimes.assistance :refer [conversation extract-tags]])

  (def conv (conversation conn 79524334 20))

  (def conn ((:conn @peer) 79524334))

  (require '[datahike.experimental.gc :as gc])

  (gc/gc! @conn)

  (require '[clojure.java.io :as io])

  -4151611394 ;; Simulacion
  
  (doseq [[t b] (d/q '[:find ?t ?b :where [?e :note/title ?t] [?e :note/body ?b]] @conn)]
    (println "#" t)
    (println b)
    (println)
   ;; write to org file in notes/chat-id/title.org
    (let [f (io/file (str "notes/" 79524334 "/" t ".org"))]
      (io/make-parents f)
      (with-open [w (io/writer f)]
        (binding [*out* w]
          (println b)))))

  (d/q '[:find ?t :where [?e :issue/title ?t]] @conn)

  (println (d/q '[:find ?b . :in $ ?t :where [?e :note/title ?t] [?e :note/body ?b]] @conn "simmie_beta_bot"))

  (doseq [[e] (d/q '[:find ?e :where [?e :note/title ?t] [?e :note/body ?b]] @conn)]
    (println e)
    (d/transact conn [[:db/retractEntity e]]))

  (require '[superv.async :refer [<?? go-try S]]
           '[ie.simm.runtimes.openai :refer [chat]]
           '[ie.simm.prompts :as pr])

;; parse instant from this string "2024-04-11T00:19:50.525695458Z"
  (defn parse-instant [s]
    (java.time.Instant/parse s))

  (parse-instant "2024-04-11T00:19:50.525695458Z")

  (str (java.time.Instant/now))

  (java.time.Instant/parse
   (chat "gpt-3.5-turbo-0125" (format "This is the time now: %s\nGiven the following message, return without comments the date time in the same format for the message. If you cannot figure the date out, return SKIP.\n%s\n\n"
                                      (str (java.time.Instant/now))
                                      "In a week at noon.")))

  (chat "gpt-3.5-turbo-0125" (format "This is the time now: %s\nThis is the time given: %s\nRefer to the given time in words relative to now, e.g. 'Tomorrow at noon' or 'Next Monday at 2:30 pm.'.\n\n"
                                     (str (java.time.Instant/now))
                                     "2028-04-29T13:00:00Z"))

  (def summary (chat #_"gpt-3.5-turbo" "gpt-4-1106-preview" (format pr/summarization-prompt conv)))

  (def tags (distinct (extract-tags summary)))

  (def new-notes
    (for [tag tags]
      (let [prompt (format pr/note-prompt tag "EMPTY" conv)]
        [tag (chat #_"gpt-3.5-turbo" "gpt-4-1106-preview" prompt)])))

  (doseq [[tag note] new-notes]
    (println tag)
    (println note))

  (def christian-update
    (let [tag "Christian"
          prompt (format "Note on: %s\n\n%s\n\n\nGiven the note above on the subect, update it in light of the following conversation summary and return the new note text only (no title). References to entities (events, places, people, organisations, businesses etc.) are syntactically expressed with double brackets as in RoamResearch, Athens or logseq, e.g. [[some topic]]. Make sure you retain these references. Be brief and succinct while keeping important facts in detail.\n\n%s" tag "Christian is a PhD student at [[University of British Columbia][UBC]] with an interest in the programming languages Clojure and Julia and is not interested in climate science." conv)]
      [tag (chat #_"gpt-3.5-turbo" "gpt-4-1106-preview" prompt)]))

  (:store @(:wrapped-atom conn))

  (d/q '[:find ?s
         :in $ [?t ...]
         :where
         [?c :conversation/summary ?s]
         [?c :conversation/tag ?t]]
       @conn
       ["agent-based modeling" "Vancouver Cherry Blossom Festival" "Vancouver International Children's Festival" "simmie_beta" "Vancouver"])

  (d/q '[:find ?t :where [?c :chat/id ?t]] @conn)

  (d/q '[:find ?m .
         :in $ ?cid
         :where
         [?m :message/chat ?c]
         [?c :chat/id ?cid]]
       @conn 79524334)

  (d/q '[:find (pull ?c [:*]) :where [?c :conversation/summary ?s]] @conn)


  ;; inspect web hook
  
  (require '[morse.api :as t]
           '[ie.simm.config :refer [config]]
           '[clj-http.client :as http])

  (def base-url "https://api.telegram.org/bot")

  (def token (:telegram-bot-token config))

  (http/get (str base-url token "/getWebhookInfo"))

  (t/set-webhook token "")


  )