(ns ie.simm.runtimes.openai
  "OpenAI effects.

   Languages: gen-ai
   This runtime is a substrate, i.e. it does not emit lower level messages and does not interfere with outgoing messages."
  (:require [libpython-clj2.require :refer [require-python]]
            [libpython-clj2.python :refer [py. py.. py.-] :as py]
            [taoensso.timbre :refer [debug warn]]
            [ie.simm.config :refer [config]]
            [clojure.core.async :refer [chan pub sub]]
            [superv.async :refer [S go-try go-loop-try <? put?]]))

(require-python '[openai :refer [OpenAI]])

(def client (OpenAI :api_key (:openai-key config)))

(def create (py.- (py.- (py.- client chat) completions) create))

(def window-sizes {"gpt-3.5-turbo-0125" 16384
                   "gpt-4-turbo" 128000
                   "gpt-4o" 128000
                   "gpt-4o-2024-08-06" 128000
                   "gpt-4o-mini" 128000 
                   "o1-preview" 128000
                   "o1-mini" 128000 })

(defn chat [model text]
  (if (>= (count text) (* 4 (window-sizes model)))
    (do
      (warn "Text too long for " model ": " (count text) (window-sizes model))
      (throw (ex-info "Sorry, the text is too long for this model. Please try a shorter text." {:type ::text-too-long :model model :text-start (subs text 0 100) :count (count text)})))
    (let [res (create :model model :messages [{:role "system" :content text}])]
      (py.- (py.- (first (py.- res choices)) message) content))))

(defn image-gen [model text]
  (let [res ((py.- (py.- client images) generate) :model model :prompt text)]
    (py.- (first (py.- res data)) url)))

(comment 
  (image-gen "dall-e-3" "a dog playing in a small house")
  
  )

(defn stt [model input-path]
  (let [audio-file ((py.- (py.- (py.- client audio) transcriptions) create) :model model :file ((py/path->py-obj "builtins.open") input-path "rb"))]
    (py.- audio-file text)))

(defn whisper-1 [input-path]
  (stt "whisper-1" input-path))

(require-python '[pathlib :refer [Path]])

(defn tts-1 [text]
  (let [res ((py.- (py.- (py.- client audio) speech) create) :model "tts-1" :voice "alloy" :input text)
        rand-path (str "/tmp/" (java.util.UUID/randomUUID) ".mp3")]
    ((py.- res stream_to_file) (Path rand-path))
    rand-path))

(defn openai [[S peer [in out]]]
  (let [p (pub in (fn [{:keys [type]}]
                    (or ({:ie.simm.languages.gen-ai/cheap-llm ::gpt-4o-mini
                          :ie.simm.languages.gen-ai/reasoner-llm ::gpt-4o
                          :ie.simm.languages.gen-ai/stt-basic ::whisper-1
                          :ie.simm.languages.gen-ai/image-gen ::dall-e-3} type)
                        :unrelated)))
        gpt-4o-mini (chan)
        _ (sub p ::gpt-4o-mini gpt-4o-mini)

        gpt-4o (chan)
        _ (sub p ::gpt-4o gpt-4o)

        whisper-1 (chan)
        _ (sub p ::whisper-1 whisper-1)

        dall-e-3 (chan)
        _ (sub p ::dall-e-3 dall-e-3)

        next-in (chan)
        _ (sub p :unrelated next-in)]
    ;; TODO use async http requests for parallelism
    ;; TODO factor dedicated translator to LLM language
    (go-loop-try S [{[m] :args :as s} (<? S gpt-4o-mini)]
                 (when s
                   (go-try S
                           (put? S out (assoc s
                                              :type :ie.simm.languages.gen-ai/cheap-llm-reply
                                              :response (try (chat "gpt-4o-mini" m) (catch Exception e e)))))
                   (recur (<? S gpt-4o-mini))))

    (go-loop-try S [{[m] :args :as s} (<? S gpt-4o)]
                 (when s
                   (go-try S
                           (put? S out (assoc s
                                              :type :ie.simm.languages.gen-ai/reasoner-llm-reply
                                              :response (try (chat "gpt-4o-2024-08-06" m) (catch Exception e e)))))
                   (recur (<? S gpt-4o))))

    (go-loop-try S [{[m] :args :as s} (<? S whisper-1)]
                 (when s
                   (go-try S
                           (put? S out (assoc s
                                              :type :ie.simm.languages.gen-ai/stt-basic-reply
                                              :response (try (stt "whisper-1" m) (catch Exception e e)))))
                   (recur (<? S whisper-1))))

    (go-loop-try S [{[m] :args :as s} (<? S dall-e-3)]
                 (when s
                   (go-try S
                           (put? S out (assoc s
                                              :type :ie.simm.languages.gen-ai/image-gen-reply
                                              :response (try (image-gen "dall-e-3" m) (catch Exception e e)))))
                   (recur (<? S dall-e-3))))

    [S peer [next-in out]]))