(ns ie.simm.runtimes.telegram
  "This telegram runtime provides obligations from a Telegram bot.
   
   Languages: chat
   It is a source runtime that does not discharge any additional outputs and does not handle other inputs."
  (:require [ie.simm.config :refer [config]]
            [morse.handlers :as h]
            [morse.polling :as p]
            [clojure.core.async :refer [put! chan pub sub close! take!]]
            [compojure.core :refer [routes POST]]
            [compojure.route :as route]
            [jsonista.core :as json]
            [ring.adapter.jetty :refer [run-jetty]]
            [superv.async :refer [go-try S <? <?? go-loop-try put?]]
            [taoensso.timbre :refer [debug]]
            [hasch.core :refer [uuid]]
            
            ;; TODO only needed for voice handling
            [morse.api :as t]
            [clj-http.client :as http]
            [clojure.java.io :as io]))


;; TODO do not eagerly fetch the voice always, but only when needed from higher level language
(defn fetch-voice! [{:keys [voice] :as msg}]
  (if-not voice msg
          (assoc msg :voice-path
                 (let [{:keys [file_id]} voice
                       token (:telegram-bot-token config)
                       ;; this should happen at least asynchronously
                       file-path (-> (t/get-file token file_id)
                                     :result
                                     :file_path)
                       rand-path (str "/tmp/" (java.util.UUID/randomUUID) ".oga")
                       _ (io/copy (:body (http/get (str "https://api.telegram.org/file/bot" token "/" file-path) {:as :byte-array}))
                                  (io/file rand-path))]
                   rand-path))))

(defn server [in]
  (let [telegram-routes (routes
                         (POST "/telegram-callback" {body :body}
                           (let [msg (-> body slurp (json/read-value json/keyword-keys-object-mapper) :message)
                                 _ (debug "received telegram message:" msg)
                                 msg (fetch-voice! msg)
                                 m {:type ::message
                                    :request-id (uuid)
                                    :msg msg}]
                             (put? S in m)
                             {:body "Thanks!"}))
                         (route/not-found "Not Found"))
        _ (debug "starting jetty telegram server")
        server (run-jetty telegram-routes {:port 8080 :join? false})]
    #(.stop server)))

(defn long-polling [in]
  (let [_ (h/defhandler bot-api
            (h/message-fn (fn [message]
                            (debug "received telegram message:" message)
                            (put! in {:type ::message
                                      :request-id (uuid)
                                      :msg (fetch-voice! message)}))))
        _ (debug "starting telegram long polling")
        _ (def channel (p/start (:telegram-bot-token config) bot-api))]
    #(p/stop channel)))

(defn telegram
  ([[S peer [in out]]]
   (telegram server [S peer [in out]]))
  ([mechanism [S peer [in out]]]
   (let [stop-fn (mechanism in)
         p (pub in (fn [_] :always))
         next-in (chan)
         _ (sub p :always next-in)
         s (chan)
         _ (sub p :never s)

         prev-out (chan)
         po (pub prev-out (fn [{:keys [type]}]
                            (or ({:ie.simm.languages.chat/send-text ::send-text
                                  :ie.simm.languages.chat/send-photo ::send-photo
                                  :ie.simm.languages.chat/send-document ::send-document} type)
                                :unrelated)))
         send-text (chan)
         _ (sub po ::send-text send-text)
         send-photo (chan)
         _ (sub po ::send-photo send-photo)
         send-document (chan)
          _ (sub po ::send-document send-document)

         _ (sub po :unrelated out)]
        ;; this only triggers when in is closed and cleans up
     (go-try S
             (<? S s)
             (debug "stopping telegram")
             (stop-fn))

     (go-loop-try S [{[chat-id msg] :args :as m} (<? S send-text)]
                  (when m
                    (debug "sending telegram message:" chat-id msg)
                    (put? S in (assoc m
                                      :type :ie.simm.languages.chat/send-text-reply
                                      :response (try (t/send-text (:telegram-bot-token config) chat-id msg)
                                                     (catch Exception e 
                                                       (debug "error sending telegram message:" e) 
                                                       e))))
                    (recur (<? S send-text))))

     (go-loop-try S [{[chat-id url] :args :as m} (<? S send-photo)]
                  (when m
                    (debug "sending telegram photo:" chat-id url)
                    (put? S in (assoc m
                                      :type :ie.simm.languages.chat/send-photo-reply
                                      :response (try
                                                  (t/send-photo (:telegram-bot-token config) chat-id url)
                                                  (catch Exception e 
                                                    (debug "error sending telegram photo:" e) 
                                                    e))))
                    (recur (<? S send-photo))))

     (go-loop-try S [{[chat-id url] :args :as m} (<? S send-document)]
                  (when m
                    (debug "sending telegram document:" chat-id url)
                    (put? S in (assoc m
                                      :type :ie.simm.languages.chat/send-document-reply
                                      :response (try
                                                  (t/send-document (:telegram-bot-token config) chat-id url)
                                                  (catch Exception e 
                                                    (debug "error sending telegram document:" e) 
                                                    e))))
                    (recur (<? S send-document))))

     [S peer [next-in prev-out]])))

(comment

  (require '[ie.simm.languages.chat :refer [send-text!]]
           '[ie.simm.languages.bindings :refer [*chans*]])

  (let [in (chan)
        out (chan)
        [_ _ [next-in prev-out]] (telegram long-polling [S nil [in out]])]
    (take! in (fn [{:keys [msg]}]
                (binding [*chans* [next-in prev-out]]
                  (println "callback" (:id (:chat msg)) msg) (send-text! (:id (:chat msg)) "pong"))))
    
    #_(close! in))


  (p/stop channel)

  )
