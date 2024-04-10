(ns ie.simm.runtimes.notes
  "Note taking runtime.
   
   Properties: stateful, persistent, durable"
  (:require [ie.simm.languages.bindings :as lb]
            [ie.simm.languages.gen-ai :refer [cheap-llm reasoner-llm stt-basic image-gen]]
            [ie.simm.prompts :as pr]
            [superv.async :refer [<?? go-try S go-loop-try <? >? put?]]
            [clojure.core.async :refer [chan pub sub mult tap timeout]]
            [taoensso.timbre :refer [debug info warn error]]
            [datahike.api :as d]
            [hasch.core :refer [uuid]]
            [clojure.string :as str]
            [clojure.java.io :as io]))

(defn summarize [conv]
  (go-try S
          (let [summarization  (<? S (reasoner-llm (format pr/summarization conv))) ]
            #_(<? S (send-text! (:id chat) (str "Note on recent conversation:\n" summarization)))
            #_(debug "with tags" (extract-tags summarization))
            summarization
            )))