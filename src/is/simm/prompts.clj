(ns is.simm.prompts
  (:require [clojure.java.io :as io]))

(def assistance (slurp (io/resource "prompts/assistance.txt")))

(def summarization (slurp (io/resource "prompts/summarization.txt")))

(def note (slurp (io/resource "prompts/note.txt")))

(def search (slurp (io/resource "prompts/search.txt")))


;; TODO unused, either factor out or remove
(def clone-prompt "Given the previous chat history, write only the next message that %s would write in their own style, language and character. Do not assist, imitate them as closely as possible.\n\n%s\n\n")

(def weather-affects-agenda "%s\n\n\nThis was the recent chat history. You are an assistant helping a user plan their day. You are given the following JSON describing the context.\n\n%s\n\nDoes the weather require special consideration for any of the agenda items? If not, just say NOOP! Otherwise keep explaining to %s directly, be brief.")

(def emails-affect-agenda "%s\n\n\nThis was the recent chat history. You are an assistant helping a user plan their day. You are given the following JSON describing the context.\n\n%s\n\nDo any of the mails affect the agenda? If not, just say NOOP! Otherwise keep explaining to %s directly, be brief and only talk about the agenda.")

(def agenda-change-needed "%s\n\nGiven the previous conversation, you need to decide whether changes to the agenda are needed. If not, answer with NOOP, else make a suggestion for how to change the agenda. Address %s directly.\n\n")




