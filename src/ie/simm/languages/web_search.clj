(ns ie.simm.languages.web-search
  (:require [ie.simm.languages.bindings :refer [*chans*]]
            [clojure.core.async :refer [chan close! pub sub]]
            [superv.async :refer [<?? go-try S go-loop-try <? >? put?]]
            [hasch.core :refer [uuid]]))

(defn search [terms]
  (let [[in out] *chans*
        req-id (uuid)
        p (pub out (fn [{:keys [type request-id]}] [type request-id]))
        reply (chan)
        _ (sub p [::search-reply req-id] reply)]
    (put? S in {:type ::search
                :terms terms
                :request-id req-id})
    (go-try S (let [{:keys [url]} (<? S reply)]
                (close! reply)
                url))))

(comment

;; TODO factor into test
  (require '[ie.simm.runtimes.brave :refer [brave]])

  (let [in (chan)
        out (chan)]
    (brave [S nil [in out]])
    (binding [*chans* [in out]]
      (<?? S (search "vancouver weather"))))

  )