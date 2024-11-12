(ns is.simm.parse
  (:require [clojure.data.json :as json]))

(defn parse-json [json-str]
  (let [markdown-body (first (.split (second (.split json-str "```json\n")) "```"))]
  (when-not (empty? markdown-body)
    (json/read-str markdown-body  :key-fn keyword))))

(comment
  (parse-json "```json\n{\"body\":\"# Hello, world!\"}\n```")

  )