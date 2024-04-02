(ns ie.simm.languages.gen-ai
   "Providing high-level generative AI functions for the runtime.
   
   The syntax is async (go-routine) functions that invoke
   lower level runtimes/downstreams with a piece of derived syntax (IR) and handle the replies transparently."
   (:require [ie.simm.languages.dispatch :refer [create-downstream-msg-handler]]))


(let [handler (create-downstream-msg-handler ::cheap-llm)]
  (defn cheap-llm [msg]
    (handler msg)))

(let [handler (create-downstream-msg-handler ::reasoner-llm)]
  (defn reasoner-llm [msg]
    (handler msg)))

(let [handler (create-downstream-msg-handler ::stt-basic)]
  (defn stt-basic [voice-path]
    (handler voice-path)))

(let [handler (create-downstream-msg-handler ::image-gen)]
  (defn image-gen [prompt]
    (handler prompt)))

(comment

  (require '[ie.simm.runtimes.openai :refer [openai]])

;; TODO needs to use pub-sub
  (let [in (chan)
        out (chan)]
    (openai [S nil [in out]])
    (binding [*chans* [in out]]
      (<?? S (cheap-llm "What is the capital of Ireland?"))))
  )