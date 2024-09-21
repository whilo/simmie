(ns ie.simm.processes.thesis
  (:require [clojure.core.async :as async :refer [timeout]]
            [superv.async :refer [go-try go-loop-try <? S]]
            [ie.simm.runtimes.openai :as openai]))

(require '[ie.simm.runtimes.openai :as openai])

(require '[datahike.api :as d])

(openai/chat "gpt-4o" "Hello, I am a chatbot. I am here to help you with your thesis. What do you need help with?")

;; define process and used languages
(comment
  (defprocess conversation [chat gen-ai core-async]
    (go-try S
            
      ))


  )

;; objective: have a complete process description that can be simulated

(defn purchase [system & args] 
  (println "Purchasing" system args)
  (last args))

(defn sale [system & args] 
  (println "Selling" system args))

(def minutes (* 60 1000))

(defn write []
  (let [topics ["structure inversion" "structure learning" "structure prediction" "structure analysis"]
        topic (rand-nth topics)
        writing-time (purchase "ch_weil@topiq.es" ::writing-time ::minutes 25)
        topic (purchase "Thesis" ::writing-issue ::issue topic)]
    (go-try S
            (when topic
              (println "Working on topic" topic)
              (<? S (timeout (* writing-time minutes)))))))

(defn pause []
  (let [recovery-time (purchase "ch_weil@topiq.es" ::recovery-time ::minutes 5)]
    (timeout (* recovery-time minutes))))


(defn yesno [question]
  (println question)
  (go-loop-try S [answer (read-line)]
               (if-not ({"yes" "no"} answer)
                 (do
                   (println "Please answer yes or no")
                   (recur (read-line)))
                 (if (= "yes" answer) true false))))

(defn read-integer [question]
  (println question)
  (go-loop-try S [answer (read-line)]
               (if-not (try (Integer/parseInt answer) (catch Exception e nil))
                 (do
                   (println "Please enter an integer")
                   (recur (read-line)))
                 answer)))

(comment
  (read-integer "How many pages did you write today?")

  )

(defn workday []
  (go-try S
          (purchase "ch_weil@topiq.es" ::simmie-fee ::usd 0.01)
          (purchase "Household Toronto Rd." ::coffee ::usd 0.5)
          (<? S (go-loop-try S []
                             (<? S (write))
                             (<? S (pause))
                             (when (<? S (yesno "Do you want to continue writing?"))
                               (recur))))
          (sale "PhD program" ::pages ::written (read-integer "How many pages did you write today?"))))

(comment

  (read-line)

  (workday)

  )