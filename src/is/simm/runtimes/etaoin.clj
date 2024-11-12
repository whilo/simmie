(ns is.simm.runtimes.etaoin
  "Headless browser control.
   
   Languages: browser
   Properties: substrate"
  (:require [etaoin.api :as e]
            [clojure.core.async :refer [chan pub sub mult tap]]
            [superv.async :refer [S go-loop-try <? put?]]))

(defn extract-body [url]
  (e/with-firefox-headless driver
    (e/go driver url)
    (e/get-element-text driver {:tag :body})))

(defn etaoin [[S peer [in out]]]
  (let [mi (mult in)
        next-in (chan)
        _ (tap mi next-in)
        pub-in (chan)
        _ (tap mi pub-in)
        pi (pub pub-in :type) 
        
        get-body (chan)
        _ (sub pi :is.simm.languages.browser/extract-body get-body)]
    (go-loop-try S [{[url] :args :as s} (<? S get-body)]
                 (when s
                   (put? S out (assoc s
                                      :type :is.simm.languages.browser/extract-body-reply
                                      :response (try (extract-body url) (catch Exception e e))))
                   (recur (<? S get-body))))

    [S peer [next-in out]]))
