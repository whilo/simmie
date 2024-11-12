(ns is.simm.languages.browser
  (:require [is.simm.languages.dispatch :refer [create-downstream-msg-handler]]))

(let [handler (create-downstream-msg-handler ::extract-body)]
  (defn extract-body [url]
    (handler url)))
