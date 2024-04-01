(ns ie.simm.languages.browser
  (:require [ie.simm.languages.dispatch :refer [create-downstream-msg-handler]]))

(let [handler (create-downstream-msg-handler ::extract-body)]
  (defn extract-body [url]
    (handler url)))
