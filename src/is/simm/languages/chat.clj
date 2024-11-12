(ns is.simm.languages.chat
  "General chat interface language. It should be a common denominator for all chat interfaces."
  (:require [is.simm.languages.dispatch :refer [create-upstream-msg-handler]]
            [clojure.spec.alpha :as s]
            [is.simm.runtimes.openai :as openai]))


;; spec for the following function
(s/def ::chat-id int?)
(s/def ::msg string?)
(s/fdef send-text! :args (s/cat :chat-id ::chat-id :msg ::msg) :ret any?)
(let [handler (create-upstream-msg-handler ::send-text)]
  (defn send-text! 
    "Send a text message to the chat."
    [chat-id msg]
    (handler chat-id msg)))

(s/fdef send-photo! :args (s/cat :chat-id ::chat-id :msg ::msg) :ret any?)
(let [handler (create-upstream-msg-handler ::send-photo)]
  (defn send-photo!
    "Send a photo to the chat."
    [chat-id msg]
    (handler chat-id msg)))

(s/fdef send-document! :args (s/cat :chat-id ::chat-id :msg ::msg) :ret any?)
(let [handler (create-upstream-msg-handler ::send-document)]
  (defn send-document!
    "Send a document to the chat."
    [chat-id msg]
    (handler chat-id msg)))

(comment


  ;; test specs in this namespace
  (s/exercise 'is.simm.languages.chat/send-text! 5)


  ;; lookup the spec of a function, e.g. send-text!
  (s/form (s/form (s/fspec ::send-text!)))

  (meta #'send-text!)



  ;; list all variables in a namespace
  (def chat-vars
    (->> (ns-publics 'is.simm.languages.chat)
         keys
         #_(filter #(re-find #"^:" %))
         (map (comp meta resolve))
         #_(map :spec)))

  (require '[is.simm.runtimes.openai :as openai])

  (->
   (openai/chat "gpt-4o-mini" (format "You are provided the following Clojure functions.\n===== functions =====\n%s\n==========\nYou need to send a reply to the user greeting them and then send them `report342.pdf`. Reply without any context with pure clojure code invoking the function."
                                      (pr-str chat-vars)))
   (.split "```clojure")
   second
   (.split "```")
   first
   read-string)

  (require '[sci.core :as sci])

  ;; get source
  (-> #'send-text!
      meta
      :file
      slurp
      println)

;; clojure.core function to retrieve source code of function, example for send-text!:
  (clojure.repl/source send-text!)

  @#'send-text!

  send-text!



  )