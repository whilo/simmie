(ns is.simm.http
  (:require [muuntaja.core :as m]
            [hiccup.page :as hp]
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

(defn response [body & [status]]
  {:status (or status 200)
   :body (str (hp/html5 body))})