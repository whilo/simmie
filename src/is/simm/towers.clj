(ns is.simm.towers
  (:require [is.simm.runtimes.brave :refer [brave]]
            [is.simm.runtimes.openai :refer [openai]]
            [is.simm.runtimes.report :refer [report]]
            [is.simm.runtimes.etaoin :refer [etaoin]]
            [is.simm.runtimes.notes :refer [notes]]
            [is.simm.runtimes.rustdesk :refer [rustdesk]]
            [is.simm.runtimes.telegram :refer [telegram long-polling]]
            [is.simm.runtimes.text-extractor :refer [text-extractor]]
            [is.simm.runtimes.assistance :refer [assistance]]
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
  (comp drain brave etaoin openai notes assistance text-extractor rustdesk telegram codrain))

(defn debug [] 
  (comp drain 
        (partial report #(println "drain: " (:type %) (:request-id %)))
        brave
        (partial report #(println "brave: " (:type %) (:request-id %)))
        etaoin
        (partial report #(println "etaoin: " (:type %) (:request-id %)))
        openai
        (partial report #(println "openai: " (:type %) (:request-id %)))
        notes
        (partial report #(println "notes: " (:type %) (:request-id %)))
        assistance
        (partial report #(println "assistance: " (:type %) (:request-id %)))
        text-extractor
        (partial report #(println "text-extractor: " (:type %) (:request-id %)))
        rustdesk
        (partial report #(println "rustdesk: " (:type %) (:request-id %)))
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
         notes
         (partial report #(println "notes: " (:type %) (:request-id %)))
         assistance
         (partial report #(println "assistance: " (:type %) (:request-id %)))
         text-extractor
         (partial report #(println "text-extractor: " (:type %) (:request-id %)))
         rustdesk
         (partial report #(println "rustdesk: " (:type %) (:request-id %)))
         (partial telegram long-polling)
         (partial report #(println "telegram: " (:type %) (:request-id %)))
         codrain))