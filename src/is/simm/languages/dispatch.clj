(ns is.simm.languages.dispatch
  "Mapping function dispatch to internal message dispatch to downstream or upstream runtimes."
  (:require [is.simm.languages.bindings :refer [*chans*]]
            [taoensso.timbre :refer [debug]]
            [clojure.core.async :refer [close! pub sub chan]]
            [hasch.core :refer [uuid]]
            [superv.async :refer [<?? go-try S go-loop-try <? >? put?]]))

(defn create-downstream-msg-handler [k]
  (let [reply-k (keyword (namespace k) (str (name k) "-reply"))]
    (fn [& args]
      (let [[in _ _ po] *chans*
            req-id (uuid)
            reply (chan)
            _ (sub po reply-k reply)]
        (put? S in {:type k
                    :args (vec args)
                    :request-id req-id})
        (go-loop-try S [r (<? S reply)]
                     (when r
                       (if (= req-id (:request-id r))
                         (let [{:keys [response]} r]
                           (close! reply)
                           response)
                         (recur (<? S reply)))))))))

(defn create-upstream-msg-handler [k]
  (let [reply-k (keyword (namespace k) (str (name k) "-reply"))]
    (fn [& args]
      (let [[_in pi out _po] *chans*
            req-id (uuid)
            reply (chan)
            _ (sub pi reply-k reply)]
        (put? S out {:type k
                     :request-id req-id
                     :args (vec args)})
        (go-loop-try S [r (<? S reply)]
                     (when r
                       (if (= req-id (:request-id r))
                         (let [{:keys [response]} r]
                           (close! reply)
                           response)
                         (recur (<? S reply)))))))))