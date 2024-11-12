(ns is.simm.runtimes.report
  "Debug runtime that just invokes a report function on messages that pass through."
  (:require [clojure.core.async :refer [chan close!]]
            [superv.async :refer [<? >? go-loop-try]]))

(defn report
  ([coruntime]
   (report println coruntime))
  ([f [S peer [in out]]]
   (let [next-in (chan)
         prev-out (chan)]
     (go-loop-try S [i (<? S in)]
                  (if i
                    (do
                      (f i)
                      (>? S next-in i)
                      (recur (<? S in)))
                    (close! next-in)))
     (go-loop-try S [o (<? S prev-out)]
                  (if o
                    (do
                      (f o)
                      (>? S out o)
                      (recur (<? S prev-out)))
                    (close! out)))
     [S peer [next-in prev-out]])))