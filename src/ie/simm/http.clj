(ns ie.simm.http
  (:require [muuntaja.core :as m]
            [reitit.ring :as ring]
            [reitit.coercion.spec]
            [reitit.ring.coercion :as rrc]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [reitit.ring.middleware.parameters :as parameters]))

(defn website-routes []
  [["/hello" {:get (fn [request]
                {:status 200
                 :body   "Hello, world!"})}]])

(defn ring-handler [routes]
  (let [routes (concat (vec (apply concat (vals routes))) (website-routes))]
    (prn "ROUTES" routes)
    (ring/ring-handler
     (ring/router
      routes
      {:data {:coercion   reitit.coercion.spec/coercion
              :muuntaja   m/instance
              :middleware [parameters/parameters-middleware
                           rrc/coerce-request-middleware
                           muuntaja/format-response-middleware
                           rrc/coerce-response-middleware]}})
     (ring/routes
      (ring/create-resource-handler {:path "/"})
      (ring/create-default-handler)))))
