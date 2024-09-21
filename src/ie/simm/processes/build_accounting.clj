(ns ie.simm.processes.build-accounting
  (:require [clojure.core.async :as async :refer [timeout]]
            [superv.async :refer [go-try go-loop-try <? S]]
            [clojure.string :as str]
            [ie.simm.runtimes.openai :as openai]
            [ie.simm.runtimes.brave :as brave]
            [ie.simm.runtimes.etaoin :as etaoin]
            [anglican.core :as anglican]
            [clojure.java.io :as io]
            [hiccup2.core :as h]
            [datahike.api :as d]
            [morse.polling :as p]))

(-> "accounting system"
    brave/search-brave
    brave/extract-url
    etaoin/extract-body)

(etaoin/extract-body "https://en.wikipedia.org/wiki/Accounting")


(defn interleave-with-delimiters
  ([coll] (interleave-with-delimiters coll "=========================================\n"))
  ([coll delimiter]
   (str/join "\n" (interleave coll (repeat delimiter)))))


(defn feedback [question]
  (println question)
  (read-line))

(defn expert [type question]
  (openai/chat "gpt-4o" (interleave-with-delimiters [(format "You are an %s." type) question])))

(defn critic [type question answer]
  (Integer/parseInt
   (openai/chat "gpt-4o" (interleave-with-delimiters [(format "You are a critic for a %s." type)
                                                      "We gave the expert this question:" question
                                                      "The expert replied with this answer:" answer
                                                      "Rate the expert's answer on a scale from 1 to 10. Only reply with a number."]))))


;; a building process loop with a fixed point, it is given an environmental context
;; in every loop we first gather and refine requirements
;; we derive subgoals to currently pursue
;; we define tests and progress metrics
;; then we do an iteration given the system so far
;; and measure the progress
;; we then check if we have reached a fixed point

subgoals (expert expert-role (str "You have this goal: " goal "\nWith these requirements: " requirements "\nAnd these subgoals derived so far:" subgoals "\nUpdate the subgoals if reasonable and enumerate them as a comma separated list: "))
            tests (expert expert-role (str "You have this goal: " goal "\nWith these requirements: " requirements "\nAnd these subgoals: " subgoals "\nAnd these tests derived so far: " tests "\n Update the tests if reasonable and return them as Clojure code string that can be passed to eval: "))
            progress-metrics (expert expert-role (str "You have this goal: " goal "\nWith these requirements: " requirements "\nAnd these subgoals: " subgoals "\nAnd these tests: " tests "\nAnd these progress metrics derived so far: " progress-metrics "\n Update the progress metrics if reasonable and return them only as a single Clojure code string that can be passed to eval directly: "))
            iteration (expert expert-role (str "You have this goal: " goal "\nWith these requirements: " requirements "\nAnd these subgoals: " subgoals "\nAnd these tests: " tests "\nAnd these progress metrics: " progress-metrics "\nAnd this iteration derived so far: " iteration "\n Update the iteration if reasonable and return it as a single Clojure code string that can be passed to eval: "))
            progress (expert expert-role (str "You have this goal: " goal "\nWith these requirements: " requirements "\nAnd these subgoals: " subgoals "\nAnd these tests: " tests "\nAnd these progress metrics: " progress-metrics "\nAnd this iteration: " iteration "\nAnd this progress derived so far: " progress "\n Update the progress metric if reasonable and return it as Clojure code. The progress metric function needs to return a real number: "))


(defn build [ctx goal]
  (let [expert-role (openai/chat "gpt-4o"
                                 (interleave-with-delimiters 
                                  ["Given this context:" ctx
                                   "What expert would you hire to pursue this goal:" goal
                                   "Answer a descriptive job title."]))]
    (loop [requirements ""
           subgoals ""
           tests ""
           progress-metrics ""
           iteration ""
           progress "" ]
      (let [requirements (expert expert-role (interleave-with-delimiters ["You have this goal:" goal
                                                                          "With these requirements derived so far:" requirements
                                                                          "What are the requirements?"]))
            subgoals (expert expert-role (interleave-with-delimiters ["You have this goal:" goal
                                                                      "With these requirements:" requirements
                                                                      "And these subgoals derived so far:" subgoals
                                                                      "Update the subgoals if reasonable and enumerate them as a comma separated list: "]))
            tests (expert expert-role (interleave-with-delimiters ["You have this goal:" goal
                                                                   "With these requirements:" requirements
                                                                   "And these subgoals:" subgoals
                                                                   "And these tests derived so far:" tests
                                                                   " Update the tests if reasonable and return them as Clojure code string that can be passed to eval: "]))
            progress-metrics (expert expert-role (interleave-with-delimiters ["You have this goal:" goal
                                                                              "With these requirements:" requirements
                                                                              "And these subgoals:" subgoals
                                                                              "And these tests:" tests
                                                                              "And these progress metrics derived so far:" progress-metrics
                                                                              " Update the progress metrics if reasonable and return them only as a single Clojure code string that can be passed to eval directly: "]))
            iteration (expert expert-role (interleave-with-delimiters ["You have this goal:" goal
                                                                       "With these requirements:" requirements
                                                                       "And these subgoals:" subgoals
                                                                       "And these tests:" tests
                                                                       "And these progress metrics:" progress-metrics
                                                                       "And this iteration derived so far:" iteration
                                                                       " Update the iteration if reasonable and return it as a single Clojure code string that can be passed to eval: "]))
            progress (expert expert-role (interleave-with-delimiters ["You have this goal:" goal
                                                                      "With these requirements:" requirements
                                                                     "And these subgoals:" subgoals
                                                                     "And these tests:" tests
                                                                     "And these progress metrics:" progress-metrics
                                                                     "And this iteration:" iteration
                                                                     "And this progress derived so far:" progress
                                                                     " Update the progress metric if reasonable and return it as Clojure code. The progress metric function needs to return a real number: "]))]
            (println "Requirements: " requirements)
            (println "Subgoals: " subgoals)
            (println "Tests: " tests)
            (println "Progress Metrics: " progress-metrics)
            (println "Iteration: " iteration)
            (println "Progress: " progress)
            [expert-role requirements subgoals tests progress-metrics iteration progress]))))


(comment

  (def build-result (build "exploring games" "Build a Snake game in Clojure without any external libraries. You have clojure.core, core.async and Datahike available."))

  (let [[expert-role requirements subgoals tests progress-metrics iteration progress] build-result]
    (println iteration))

  )
