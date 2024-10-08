(ns ie.simm.languages.chat
  "General chat interface language. It should be a common denominator for all chat interfaces."
  (:require [ie.simm.languages.dispatch :refer [create-upstream-msg-handler]]))


(let [handler (create-upstream-msg-handler ::send-text)]
  (defn send-text! [chat-id msg]
    (handler chat-id msg)))

(let [handler (create-upstream-msg-handler ::send-photo)]
  (defn send-photo! [chat-id msg]
    (handler chat-id msg)))

(let [handler (create-upstream-msg-handler ::send-document)]
  (defn send-document! [chat-id msg]
    (handler chat-id msg)))