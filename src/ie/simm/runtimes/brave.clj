(ns ie.simm.runtimes.brave
  "This runtime provides brave search results.
 
   Languages: web-search"
  (:require  [ie.simm.config :refer [config]]
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

(defn second-url [result]
  (-> result :web :results second :url))


(defn brave [[S peer [in out]]]
  (let [m (mult in)
        next-in (chan)
        _ (tap m next-in)
        pub-in (chan)
        _ (tap m pub-in)

        p (pub pub-in :type)
        search (chan)
        _  (sub p :ie.simm.languages.web-search/search search)]
    (go-loop-try S [s (<? S search)]
                 (when s
                   (debug "searching brave for" s)
                   (put? S out (assoc s
                                      :type :ie.simm.languages.web-search/search-reply
                                      :response (second-url (search-brave (first (:args s))))))
                   (recur (<? S search))))
    [S peer [next-in out]]))