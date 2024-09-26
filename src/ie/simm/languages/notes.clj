(ns ie.simm.languages.notes
  "General chat interface language. It should be a common denominator for all chat interfaces."
  (:require [ie.simm.languages.dispatch :refer [create-downstream-msg-handler]]))


(let [handler (create-downstream-msg-handler ::related-notes)]
  (defn related-notes [conn conv]
    (handler conn conv)))

