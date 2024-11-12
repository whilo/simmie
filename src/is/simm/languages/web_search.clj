(ns is.simm.languages.web-search
  (:require [is.simm.languages.dispatch :refer [create-downstream-msg-handler]]))

(let [handler (create-downstream-msg-handler ::search)]
  (defn search [terms]
    (handler terms)))


(comment

;; TODO factor into test
  (require '[is.simm.runtimes.brave :refer [brave]])

  (let [in (chan)
        out (chan)]
    (brave [S nil [in out]])
    (binding [*chans* [in out]]
      (<?? S (search "vancouver weather"))))

  )