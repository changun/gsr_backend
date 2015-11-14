(ns gsr-backend.core
  (:use [compojure.route :only [files not-found]]
        [compojure.handler :only [site]] ; form, query params decode; cookie; session, etc
        [compojure.core :only [defroutes GET POST DELETE ANY context]]
        org.httpkit.server))


(defn process [data]
  (println data)
  (send! channel data)
  )

(defn handler [request]
  (println request)
  (with-channel request channel
                (if (websocket? channel)
                  (println "WebSocket channel")
                  (println "HTTP channel"))
                (on-receive channel process)
                (on-close channel (fn [status] (println "channel closed, " status)))
                ))



(defroutes all-routes
           (GET "/ws" [] handler))

(run-server (site #'all-routes) {:port 8080})