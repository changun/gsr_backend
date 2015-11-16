(ns gsr-backend.core
  (:require [clj-time.coerce :as c]
            [clj-time.core :as t]
            [org.httpkit.client :as http]
            [ring.util.codec :refer [url-encode form-encode]]
            [cheshire.core :refer :all]
            [monger.core :as mg]
            [monger.collection :as mc]
            [monger.joda-time]
            )
  (:use
        [gsr-backend.gmail]
        [compojure.route :only [files not-found]]
        [compojure.handler :only [site]]                    ; form, query params decode; cookie; session, etc
        [compojure.core :only [defroutes GET POST DELETE ANY context]]
        org.httpkit.server)
  (:import (java.util UUID)
           (java.util.concurrent TimeoutException)
           (java.io IOException)))

(def conn (mg/connect))
(def db   (mg/get-db conn "gsr"))

(defn process
  [channel data device-name]
  (try
    (let [data (parse-string data true)]

      (println (merge
                 data
                 {:time (c/from-long (long (:time data)))
                  :device-name device-name}
                 ))
      (mc/insert db "readings"
                 (merge
                   data
                   {:time (c/from-long (long (:time data)))
                    :device-name device-name}
                   ))
      )
    (catch Exception e (println e)
                       ))
  (send! channel "")
  )



(defn sync-mail [device-name access-token]
  (loop [last nil]
    (println "Last:" last)
    (let [msgs (take 10 (take-while #(not= last %) (msg-sequence "label:sent" access-token)))]
      (println msgs)
      (try
        (doseq [msg msgs]
          (let [msg (get-msg (:id msg) access-token)]
            (println msg)
            (mc/save db "mails"
                     (assoc
                       msg
                       :device-name device-name
                       :time (c/from-long (Long/valueOf ^String (:internalDate msg)))
                       :_id (:id msg)))
            )

          )
        (catch Exception e (println e)
                           ))
      (Thread/sleep 3000)
      (recur (or (first msgs) last))
      )
    )
  )

(defn handler [request]
  (with-channel request channel
                (if (websocket? channel)
                  (println "WebSocket channel")
                  (println "HTTP channel"))
                (let [device-name (:deviceName (:params request))
                      access-token (get-access-token (:authCode (:params request)))
                      mail-syncer (future
                                    (sync-mail device-name access-token)
                                    )]

                  (on-receive channel #(process channel  % device-name))
                  (on-close channel (fn [status]
                                      (future-cancel mail-syncer)
                                      (println "channel closed, " status)))
                  )
                ))
(defn query [request]
  (let [{:keys [start]} (:params request)
        start (or start (t/minus (t/now) (t/minutes 10)))
        ]
    {:body (generate-string
             (merge
               {:mail (->> (mc/find-maps db "mails" {:time {:$gte start}})
                            (map #(dissoc % :_id)))

                }
               (->> (mc/find-maps db "readings" {:time {:$gte start}})
                    (map #(dissoc % :_id))
                    (group-by :type)
                    ))
             )

     }
    )
  )


(defroutes all-routes
           (GET "/ws" [] handler)
           (GET "/query" [] query)
           (files "/static/")
           )

(run-server (site #'all-routes) {:port 8080})