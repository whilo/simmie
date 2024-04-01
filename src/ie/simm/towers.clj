(ns ie.simm.towers
  (:require [ie.simm.runtimes.brave :refer [brave]]
            [ie.simm.runtimes.openai :refer [openai]]
            [ie.simm.runtimes.report :refer [report]]
            [ie.simm.runtimes.etaoin :refer [etaoin]]
            [ie.simm.runtimes.telegram :refer [telegram long-polling]]
            [ie.simm.runtimes.relational-assistance :refer [relational-assistance]]))


(defn default []
  (comp brave etaoin openai relational-assistance telegram))

(defn debug [] 
  (comp brave
        (partial report #(println "brave: " %))
        etaoin
        (partial report #(println "etaoin: " %))
        openai
        (partial report #(println "openai: " %))
        relational-assistance
        (partial report #(println "relational-assistance: " %))
        telegram
        (partial report #(println "telegram: " %))))

(defn test-tower []
  (comp brave
        (partial report #(println "brave: " (:type %)))
        etaoin
        (partial report #(println "etaoin: " (:type %)))
        openai
        (partial report #(println "openai: " (:type %)))
        relational-assistance
        (partial report #(println "relational-assistance: " (:type %)))
        (partial telegram long-polling)
        (partial report #(println "telegram: " (:type %)))))


(comment

  (require '[clojure.core.async :refer [chan put! take! close! timeout]]
           '[hasch.core :refer [uuid]]
           '[superv.async :refer [S go-try <? go-loop-try]])

  (let [in (chan)
        out (chan)
        peer (atom {})
        [_ _ [next-in prev-out]] ((test-tower) [S peer [in out]])]
    (put! in {:type :ie.simm.runtimes.telegram/message
              :request-id (uuid)
              :msg {:message_id 55, :from {:id 79524334, :is_bot false, :first_name "Christian", :username "wh1lo", :language_code "en"}, 
                    :chat {:id 79524334, :first_name "Christian", :username "wh1lo", :type "private"}, :date 1711889541, :text "hey"}})

                    ;; print all messages from out
    (go-loop-try S [msg (<? S out)]
                 (if msg
                   (do
                     (println "out" (:type msg))
                     (recur (<? S out)))
                   (println "out closed")))
    ;; loop instead
    (go-loop-try S [msg (<? S next-in)]
                 (if msg
                   (do
                     (println "next-in" (:type msg))
                     (recur (<? S next-in)))
                   (println "next-in closed")))

    #_(take! prev-out (fn [{:keys [msg]}] (println "callback" msg)))
    (go-try S
            (<? S (timeout (* 60 1000)))
            (close! in)))


  
  )