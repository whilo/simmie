(ns is.simm.runtimes.brave
  "This runtime provides brave search results.
 
   Languages: web-search"
  (:require  [is.simm.config :refer [config]]
             [clj-http.client :as http]
             [jsonista.core :as json]
             [clojure.core.async :refer [chan pub sub mult tap]]
             [superv.async :refer [S <? go-loop-try put?]]
             [taoensso.timbre :refer [debug]]
             [clojure.string :as s]))

(defn search-brave [terms]
  (->
   (http/get "https://api.search.brave.com/res/v1/web/search" {:query-params {:q terms
                                                                              :count 5}
                                                               :headers {"X-Subscription-Token" (:brave-token config)}})
   :body
   (json/read-value json/keyword-keys-object-mapper)))

(defn extract-url [result]
  (-> result :web :results first :url))


(defn brave [[S peer [in out]]]
  (let [m (mult in)
        next-in (chan)
        _ (tap m next-in)
        pub-in (chan)
        _ (tap m pub-in)

        p (pub pub-in :type)
        search (chan)
        _  (sub p :is.simm.languages.web-search/search search)]
    (go-loop-try S [s (<? S search)]
                 (when s
                   (debug "searching brave for" s)
                   (put? S out (assoc s
                                      :type :is.simm.languages.web-search/search-reply
                                      :response (try (extract-url (search-brave (first (:args s)))) (catch Exception e e))))
                   (recur (<? S search))))
    [S peer [next-in out]]))