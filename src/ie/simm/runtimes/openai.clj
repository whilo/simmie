(ns ie.simm.runtimes.openai
  "OpenAI effects.

   Languages: llm
   This runtime is a substrate, i.e. it does not emit lower level messages and does not interfere with outgoing messages."
  (:require [libpython-clj2.require :refer [require-python]]
            [libpython-clj2.python :refer [py. py.. py.-] :as py]
            [taoensso.timbre :refer [debug]]
            [ie.simm.config :refer [config]]
            [clojure.core.async :refer [chan pub sub]]
            [superv.async :refer [S go-loop-try <? put?]]))

(require-python '[openai :refer [OpenAI]])

(def client (OpenAI :api_key (:openai-key config)))

(defn chat [model text]
  (let [res ((py.- (py.- (py.- client chat) completions) create) :model model :messages [{:role "system" :content text}])]
    (py.- (py.- (first (py.- res choices)) message) content)))

(defn image-gen [model text]
  (let [res ((py.- (py.- client images) generate) :model model :prompt text)]
    (py.- (first (py.- res data)) url)))

(defn tts [model input-path]
  (let [audio-file ((py.- (py.- (py.- client audio) transcriptions) create) :model model :file ((py/path->py-obj "builtins.open") input-path "rb"))]
    (py.- audio-file text)))

(defn whisper-1 [input-path]
  (tts "whisper-1" input-path))

(require-python '[pathlib :refer [Path]])

(defn tts-1 [text]
  (let [res ((py.- (py.- (py.- client audio) speech) create) :model "tts-1" :voice "alloy" :input text)
        rand-path (str "/tmp/" (java.util.UUID/randomUUID) ".mp3")]
    ((py.- res stream_to_file) (Path rand-path))
    rand-path))

(defn openai [[S peer [in out]]]
  (let [p (pub in (fn [{:keys [type]}]
                    (or ({:ie.simm.languages.gen-ai/cheap-llm ::gpt-35-turbo
                          :ie.simm.languages.gen-ai/reasoner-llm ::gpt-4-1106-preview
                          :ie.simm.languages.gen-ai/tts-basic ::whisper-1} type)
                        :unrelated)))
        gpt-35-turbo (chan)
        _ (sub p ::gpt-35-turbo gpt-35-turbo)

        gpt-4-1106-preview (chan)
        _ (sub p ::gpt-4-1106-preview gpt-4-1106-preview)

        whisper-1 (chan)
        _ (sub p ::whisper-1 whisper-1)

        next-in (chan)
        _ (sub p :unrelated next-in)]
    ;; TODO use async http requests for parallelism
    ;; TODO factor dedicated translator to LLM language
    (go-loop-try S [{[m] :args :as s} (<? S gpt-35-turbo)]
                 (when s
                   (put? S out (assoc s
                                      :type :ie.simm.languages.gen-ai/cheap-llm-reply
                                      :response (chat "gpt-3.5-turbo" m)))
                   (recur (<? S gpt-35-turbo))))

    (go-loop-try S [{[m] :args :as s} (<? S gpt-4-1106-preview)]
                 (when s
                   (put? S out (assoc s
                                      :type :ie.simm.languages.gen-ai/reasoner-llm-reply
                                      :response (chat "gpt-4-1106-preview" m)))
                   (recur (<? S gpt-4-1106-preview))))

    (go-loop-try S [{[m] :args :as s} (<? S whisper-1)]
                 (when s
                   (put? S out (assoc s
                                      :type :ie.simm.languages.gen-ai/tts-basic-reply
                                      :response (tts "whisper-1" m)))
                   (recur (<? S whisper-1))))

    [S peer [next-in out]]))