(ns gsr-backend.core
  (:use [compojure.route :only [files not-found]]
        [compojure.handler :only [site]] ; form, query params decode; cookie; session, etc
        [compojure.core :only [defroutes GET POST DELETE ANY context]]
        org.httpkit.server))



(defn handler [request]
  (with-channel request channel
                (on-close channel (fn [status] (println "channel closed, " status)))
                (on-receive channel (fn [data]
                                      (send! channel data)))))



(defroutes all-routes
           (GET "/ws" [] handler))

(run-server (site #'all-routes) {:port 8080})