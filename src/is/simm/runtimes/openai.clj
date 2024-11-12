(ns is.simm.runtimes.openai
  "OpenAI effects.

   Languages: gen-ai
   This runtime is a substrate, i.e. it does not emit lower level messages and does not interfere with outgoing messages."
  (:require [libpython-clj2.require :refer [require-python]]
            [libpython-clj2.python :refer [py. py.. py.-] :as py]
            [taoensso.timbre :refer [debug warn]]
            [is.simm.config :refer [config]]
            [clojure.core.async :refer [chan promise-chan pub sub put! <!] :as async]
            [superv.async :refer [S go-try go-loop-try <? put?]]
            [clojure.data.json :as json]
            [babashka.http-client :as http]
            [clojure.java.io :as io])
  (:import [java.util Base64]
           [java.util.function Function]))

(def api-key (:openai-key config))

(defn encode-image [image-path]
  (with-open [input-stream (io/input-stream image-path)]
    (let [image-bytes (.readAllBytes input-stream)]
      (.encodeToString (Base64/getEncoder) image-bytes))))

(def headers
  {"Content-Type"  "application/json"
   "Authorization" (str "Bearer " api-key)})

(defn payload [model content]
  (json/write-str
    {"model" model
     "messages" [{"role" "user"
                  "content" content
                  #_[{"type" "text"
                      "text" text}
                     {"type" "image_url"
                      "image_url" {"url" (str "data:image/jpeg;base64," base64-image)}}]}]
     ;"max_tokens" 300
     }))

(def window-sizes {"gpt-3.5-turbo-0125" 16384
                   "gpt-4-turbo" 128000
                   "gpt-4o" 128000
                   "gpt-4o-2024-08-06" 128000
                   "gpt-4o-mini" 128000 
                   "o1-preview" 128000
                   "o1-mini" 128000 })

(defn chat [model content]
  (let [res (promise-chan)
        cf (http/post "https://api.openai.com/v1/chat/completions"
                      {:headers headers
                       :body (payload model content)
                       :async true})]
    (-> cf
        (.thenApply (reify Function
                      (apply [_ response]
                        (put! res (-> response
                                      :body
                                      json/read-str
                                      (get "choices")
                                      first
                                      (get "message")
                                      (get "content"))))))
        (.exceptionally (reify Function
                          (apply [_ e]
                            (put! res (ex-info "Error in OpenAI chat." {:type :error-in-openai :error e}))))))
    res))


(defn text-chat [model text]
  (let [res (chan)]
    (if (>= (count text) (* 4 (window-sizes model)))
      (do (warn "Text too long for " model ": " (count text) (window-sizes model))
          (put! res (ex-info "Sorry, the text is too long for this model. Please try a shorter text." 
                             {:type ::text-too-long :model model :text-start (subs text 0 100) :count (count text)}))
          res)
      (chat model [{"type" "text" "text" text}]))))

(comment

  (async/<!! (text-chat "gpt-4o-mini" "What is the capital of France?"))
  

;; vision chat
  (import '[java.nio.file Files Paths]
          '[java.util Base64])

  (def test-image "/home/ubuntu/screenshots/frames_0003.jpg")

  (defn read-file-as-byte-array [file-path]
    (Files/readAllBytes (Paths/get file-path (make-array String 0))))

  (defn encode-to-base64 [byte-array]
    (let [encoder (Base64/getEncoder)]
      (.encodeToString encoder byte-array)))

  (def test-base64 (encode-to-base64 (read-file-as-byte-array test-image)))

  (def test-image-query (create :model "gpt-4o-mini" :messages [{:role "user" :content [{:type "text" :text "What is in this image? Describe all the visual information as precisely as possible."} {:type "image_url" :image_url {:url (str "data:image/jpeg;base64," test-base64)}}]}]))


  )

;; TODO port these also to babashka
(require-python '[openai :refer [OpenAI]])

(def client (OpenAI :api_key (:openai-key config)))

(def create (py.- (py.- (py.- client chat) completions) create))


(defn py-chat [model text]
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
                    (or ({:is.simm.languages.gen-ai/cheap-llm ::gpt-4o-mini
                          :is.simm.languages.gen-ai/reasoner-llm ::gpt-4o
                          :is.simm.languages.gen-ai/stt-basic ::whisper-1
                          :is.simm.languages.gen-ai/image-gen ::dall-e-3} type)
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
                                              :type :is.simm.languages.gen-ai/cheap-llm-reply
                                              :response 
                                              (<! (text-chat "gpt-4o-mini" m)) 
                                              #_(try (py-chat "gpt-4o-mini" m) (catch Exception e e)))))
                   (recur (<? S gpt-4o-mini))))

    (go-loop-try S [{[m] :args :as s} (<? S gpt-4o)]
                 (when s
                   (go-try S
                           (put? S out (assoc s
                                              :type :is.simm.languages.gen-ai/reasoner-llm-reply
                                              :response 
                                              (<! (text-chat "gpt-4o-2024-08-06" m)) 
                                              #_(try (py-chat "gpt-4o-2024-08-06" m) (catch Exception e e)))))
                   (recur (<? S gpt-4o))))

    (go-loop-try S [{[m] :args :as s} (<? S whisper-1)]
                 (when s
                   (go-try S
                           (put? S out (assoc s
                                              :type :is.simm.languages.gen-ai/stt-basic-reply
                                              :response (try (stt "whisper-1" m) (catch Exception e e)))))
                   (recur (<? S whisper-1))))

    (go-loop-try S [{[m] :args :as s} (<? S dall-e-3)]
                 (when s
                   (go-try S
                           (put? S out (assoc s
                                              :type :is.simm.languages.gen-ai/image-gen-reply
                                              :response (try (image-gen "dall-e-3" m) (catch Exception e e)))))
                   (recur (<? S dall-e-3))))

    [S peer [next-in out]]))