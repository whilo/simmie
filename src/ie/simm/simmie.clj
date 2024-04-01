(ns ie.simm.simmie
  (:require [taoensso.timbre :as log]
            [kabel.peer :as peer]
            [kabel.http-kit :as http-kit]
            [superv.async :refer [S <? <??]]
            [ie.simm.towers :refer [default debug]])
  (:gen-class))


(log/set-min-level! :debug)

(defn -main
  "Starting a peer server."
  [& _args]
  (let [server-id #uuid "1eeb9d11-a927-4710-9a82-48a82fb0d7b1"
        url "ws://localhost:47291"
        http-handler (http-kit/create-http-kit-handler! S url server-id)
        server (peer/server-peer S http-handler server-id
                                ;; here you can plug in your (composition of) middleware(s)
                                (default)
                                ;; we chose no serialization (pr-str/read-string by default)
                                identity
                                ;; we could also pick the transit middleware
                                #_transit)]
    (<?? S (peer/start server))
    (log/info "Server started.")))
