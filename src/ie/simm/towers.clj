(ns ie.simm.towers
  (:require [ie.simm.runtimes.brave :refer [brave]]
            [ie.simm.runtimes.openai :refer [openai]]
            [ie.simm.runtimes.report :refer [report]]
            [ie.simm.runtimes.etaoin :refer [etaoin]]
            [ie.simm.runtimes.telegram :refer [telegram long-polling]]
            [ie.simm.runtimes.relational-assistance :refer [relational-assistance]]
            [kabel.peer :refer [drain]]
            [clojure.core.async :refer [close!]]
            [superv.async :refer [go-loop-super <? S]]))

(defn codrain [[S peer [in out]]]
  (go-loop-super S [o (<? S out)]
                 (if o
                   (recur (<? S out))
                   (close! in)))
  [S peer [in out]])

(defn default []
  (comp drain brave etaoin openai relational-assistance telegram))

(defn debug [] 
  (comp drain 
        (partial report #(println "drain: " %))
        brave
        (partial report #(println "brave: " %))
        etaoin
        (partial report #(println "etaoin: " %))
        openai
        (partial report #(println "openai: " %))
        relational-assistance
        (partial report #(println "relational-assistance: " %))
        telegram
        (partial report #(println "telegram: " %))
        codrain))

(defn test-tower []
  (comp  drain
         (partial report #(println "drain: " (:type %)))
         brave
         (partial report #(println "brave: " (:type %)))
         etaoin
         (partial report #(println "etaoin: " (:type %)))
         openai
         (partial report #(println "openai: " (:type %)))
         relational-assistance
         (partial report #(println "relational-assistance: " (:type %)))
         (partial telegram long-polling)
         (partial report #(println "telegram: " (:type %)))
         codrain))