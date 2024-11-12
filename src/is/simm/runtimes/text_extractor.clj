(ns is.simm.runtimes.text-extractor
  "Extract texts from incoming message and augment the text.
   
   Properties: stateless"
  (:require [is.simm.languages.bindings :as lb]
            [is.simm.languages.gen-ai :refer [cheap-llm stt-basic]]
            [is.simm.languages.chat :refer [send-text!]]
            [is.simm.languages.browser :refer [extract-body]]
            [is.simm.prompts :as pr]
            [superv.async :refer [<?? go-try S go-loop-try <? >? put? go-for] :as sasync]
            [clojure.core.async :refer [chan pub sub mult tap timeout] :as async]
            [taoensso.timbre :refer [debug info warn error]]
            [hasch.core :refer [uuid]]
            [clojure.string :as str]
            #_[libpython-clj2.require :refer [require-python]]
            #_[libpython-clj2.python :refer [py. py.. py.-] :as py]
            
            [babashka.http-client :as http]
            [clojure.data.json :as json]
            [clojure.string :as str]
            ))

(def watch-url "https://www.youtube.com/watch?v=")

(defn- raise-http-errors [response video-id]
  (let [status (:status response)]
    (if (= 200 status)
      (:body response)
      (throw (ex-info (str "YouTube request failed for video: " video-id)
                      {:status status
                       :video-id video-id})))))

(defn fetch-video-html [client video-id]
  (let [response @(http/get (str watch-url video-id) {:headers {"Accept-Language" "en-US"} :client client :async true})]
    (raise-http-errors response video-id)))

(defn extract-captions-json [html video-id]
  (if-not (re-find #"\"captions\":" html)
    (throw (ex-info "Transcripts are not found for this video" {:video-id video-id})))

  (let [captions-data (-> (str/split html #"\"captions\":")
                          second
                          (str/split #"\"videoDetails")
                          first
                          clojure.string/trim
                          (json/read-str :key-fn keyword))]
    (if-let [captions-json (get captions-data :playerCaptionsTracklistRenderer)]
      (if (contains? captions-json :captionTracks)
        captions-json
        (throw (ex-info "No transcript available for this video" {:video-id video-id})))
      (throw (ex-info "Transcripts are disabled for this video" {:video-id video-id})))))

(defn fetch-transcript [client video-id]
  (let [html (fetch-video-html client video-id)
        captions-json (extract-captions-json html video-id)]
    (for [caption (:captionTracks captions-json)]
      {:language (:languageCode caption)
       :url (:baseUrl caption)})))

(defn fetch-transcript-data [client transcript-url]
  (let [response @(http/get transcript-url {:headers {"Accept-Language" "en-US"} :client client :async true})]
    (->> (json/read-str (:body (raise-http-errors response transcript-url))
                        :key-fn keyword)
         :events
         (map #(select-keys % [:segs :tOffsetMs :dDurationMs])))))

(def client (http/client (assoc-in http/default-client-opts [:proxy] {:host "localhost" :port 8118})))

;; Example usage with async:


(defn youtube-transcript [video-id]
  (-> (fetch-transcript client video-id)
      first
      :url
      (http/get {:client client})
      :body
      (str/replace #"<[^>]*>" " ")))


#_(require-python '[youtube_transcript_api :refer [YouTubeTranscriptApi]])

#_(defn youtube-transcript [video-id]
  ;; " ".join([t['text'] for t in transcript])
  (let [transcript (py. YouTubeTranscriptApi get_transcript video-id)]
    (str/join " " (map :text transcript))))

(comment
  (fetch-transcript client "20TAkcy3aBY")

  (youtube-transcript "wkH1dpr-p_4")

  )

(defn extract-url [S text chat]
  (go-try S
          (if-let [;; if text matches http or https web URL extrect URL with regex
                   url (if text (re-find #"https?://\S+" text) "")]
            (if-let [;; extract youtube video id from URL
                     youtube-id (second (or (re-find #"youtube.com/watch\?v=([^&]+)" url)
                                            (re-find #"youtu.be/([^\?]+)" url)))]
              (try
                (debug "summarizing youtube transcript" youtube-id)
                (let [transcript (youtube-transcript youtube-id)
                      summary (<? S (cheap-llm (format pr/summarization transcript)))
                      summary (str "Youtube transcript summary:\n" summary "\n" url)]
                  (<? S (send-text! (:id chat) summary))
                  summary)
                (catch Exception e
                  (warn "Could not extract transcript from youtube video" youtube-id e)
                  text))
              (try
                (let [body (<? S (extract-body url))
                      summary (<? S (cheap-llm (format pr/summarization body)))]
                  (<? S (send-text! (:id chat) summary))
                  (str "Website summary:\n" summary "\n" url))
                (catch Exception e
                  (warn "Could not extract body from URL" url e)
                  text)))
            text)))

(defn text-extractor
  [[S peer [in out]]]
  ;; pass everything from in to next-in for the next middleware
  ;; and create publication channel for runtime context
  (let [mi (mult in)
        pub-in (chan)
        _ (tap mi pub-in)
        ;; pub for internal function dispatch
        pi (pub pub-in :type)

        ;; internal subscriptions for this runtime context
        pub-subs (chan)
        _ (tap mi pub-subs)
        p (pub pub-subs (fn [{:keys [type]}]
                          (or ({:is.simm.runtimes.telegram/message ::message} type)
                              :unrelated)))
        msg-ch (chan 1000)
        next-in (chan 1000)
        _ (sub p ::message msg-ch)
        _ (sub p :unrelated next-in)

       ;; do the same in reverse for outputs from below
        prev-out (chan)
        mo (mult prev-out)
        _ (tap mo out)
        pub-out (chan)
        _ (tap mo pub-out)
        po (pub pub-out :type)]
    (go-loop-try S [m (<? S msg-ch)]
                 (when m
                   (binding [lb/*chans* [next-in pi out po]]
                     (let [{:keys [msg]
                            {:keys [text chat voice-path from]} :msg} m]
                       (try
                         (let [_ (debug "received message" m)
                               text (if-not voice-path text
                                            (let [transcript (<? S (stt-basic voice-path))
                                                  transcript (str "Voice transcript " (:username from) ":\n" transcript)]
                                              (when text (warn "Ignoring text in favor of voice message"))
                                              (debug "created transcript" transcript)
                                              (<? S (send-text! (:id chat) transcript))
                                              transcript))
                               text (<? S (extract-url S text chat))
                               msg (assoc msg :text text)
                               m (assoc m :msg msg)]
                           (>? S next-in m))
                         (catch Exception e
                           (let [error-id (uuid)]
                             (error "Could not process message(" error-id "): " m e)
                             (<? S (send-text! (:id chat) (str "Sorry, I could not process your message. Error: " error-id))))))))
                   (recur (<? S msg-ch))))
    [S peer [next-in prev-out]]))
