(ns ie.simm.simmie
  (:require [taoensso.timbre :as log]
            [taoensso.timbre.appenders.core :as appenders]
            [superv.async :refer [S <? <??]]
            [clojure.core.async :refer [chan close!]]
            [ie.simm.towers :refer [default debug test-tower]]
            [nrepl.server :refer [start-server stop-server]])
  (:gen-class))

(defonce server (start-server :port 37888))

(log/merge-config!
  {:appenders {:spit (appenders/spit-appender {:fname "server.log"})}})

(log/set-min-level! :debug)

(defn -main
  "Starting a peer server."
  [& _args]
  (let [in (chan)
        out (chan)
        peer (atom {})]
    ((debug) [S peer [in out]])
    (log/info "Server started.")
    ;; HACK to block
    (<?? S (chan))))


(comment

  ;; pull above let into top level defs

  (def in (chan))

  (def out (chan))

  (def peer (atom {}))

  (def tower ((debug) #_(test-tower) [S peer [in out]]))

  (close! in)

  (require '[datahike.api :as d])

  (def conn (second (first (:conn @peer))))

  (:store @(:wrapped-atom conn))

  (d/q '[:find ?s
         :in $ [?t ...]
         :where
         [?c :conversation/summary ?s]
         [?c :conversation/tag ?t]]
       @conn
       ["agent-based modeling" "Vancouver Cherry Blossom Festival" "Vancouver International Children's Festival" "simmie_beta" "Vancouver"])

  (d/q '[:find ?t :where [?c :chat/id ?t]] @conn)

  (d/q '[:find (count ?m) .
         :in $ ?cid
         :where
         [?m :message/chat ?c]
         [?c :chat/id ?cid]]
       @conn -4199252441)

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