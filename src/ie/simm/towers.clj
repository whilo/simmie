(ns ie.simm.towers
  (:require [ie.simm.runtimes.brave :refer [brave]]
            [ie.simm.runtimes.openai :refer [openai]]
            [ie.simm.runtimes.report :refer [report]]
            [ie.simm.runtimes.etaoin :refer [etaoin]]
            [ie.simm.runtimes.telegram :refer [telegram long-polling]]
            [ie.simm.runtimes.text-extractor :refer [text-extractor]]
            [ie.simm.runtimes.assistance :refer [assistance]]
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
  (comp drain brave etaoin openai assistance text-extractor telegram codrain))

(defn debug [] 
  (comp drain 
        (partial report #(println "drain: " (:type %) (:request-id %)))
        brave
        (partial report #(println "brave: " (:type %) (:request-id %)))
        etaoin
        (partial report #(println "etaoin: " (:type %) (:request-id %)))
        openai
        (partial report #(println "openai: " (:type %) (:request-id %)))
        assistance
        (partial report #(println "assistance: " (:type %) (:request-id %)))
        text-extractor
        (partial report #(println "text-extractor: " (:type %) (:request-id %)))
        telegram
        (partial report #(println "telegram: " (:type %) (:request-id %)))
        codrain))

(defn test-tower []
  (comp  drain
         (partial report #(println "drain: " (:type %) (:request-id %)))
         brave
         (partial report #(println "brave: " (:type %) (:request-id %)))
         etaoin
         (partial report #(println "etaoin: " (:type %) (:request-id %)))
         openai
         (partial report #(println "openai: " (:type %) (:request-id %)))
         assistance
         (partial report #(println "assistance: " (:type %) (:request-id %)))
         text-extractor
         (partial report #(println "text-extractor: " (:type %) (:request-id %)))
         (partial telegram long-polling)
         (partial report #(println "telegram: " (:type %) (:request-id %)))
         codrain))