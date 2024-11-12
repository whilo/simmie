(ns is.simm.peer)

(defn add-routes! [peer middleware routes]
  (swap! peer assoc-in [:http :routes middleware] routes))