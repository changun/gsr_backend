(ns gsr-backend.core
  (:require [clj-time.coerce :as c]
            [clj-time.core :as t]
            [org.httpkit.client :as http]
            [ring.util.codec :refer [url-encode form-encode]]
            [cheshire.core :refer :all]
            [monger.core :as mg]
            [monger.collection :as mc]
            [monger.joda-time]
            [monger.query :as query]
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
    (catch Exception e (println e)))
  (send! channel ""))



(defn peak-detection [time]
  (try (let [mail-period (->> (mc/find-maps db "readings" {:type "gsr"
                                                           :time {:$gt (t/minus time (t/seconds 5))
                                                                  :$lt (t/plus time (t/seconds 4))}} [:reading])
                              (map :reading))
             baseline-period (->> (mc/find-maps db "readings" {:type "gsr"
                                                               :time {:$gt (t/minus time (t/minutes 1))
                                                                      :$lt (t/minus time (t/seconds 5))}} [:reading])
                                  (map :reading))]
         (if (and (seq mail-period) (seq baseline-period) (> (count baseline-period) 20))
           (let [mean (/ (apply + baseline-period) (count baseline-period))
                 sigma (->> (map #(- % mean) baseline-period)
                            (map #(Math/pow % 2))
                            (apply +)
                            )
                 sigma (-> sigma
                           (/ (- (count baseline-period) 1))
                           (Math/pow 0.5)
                           )
                 mail-mean (/ (apply + mail-period) (count mail-period))
                 peak? (> (/ (-  mail-mean mean) sigma) 1)]
             (println "Peak? " peak?)
             peak?
             )

           )


         )
       (catch Exception e nil))
  )

(defn sync-mail [device-name access-token]
  (loop [last nil]
    (println "Last:" last)
    (let [msgs (take 10 (take-while #(not= last %) (msg-sequence "label:sent" access-token)))]
      (println msgs)
      (try
        (doseq [msg msgs]
          (let [msg (get-msg (:id msg) access-token)
                time (c/from-long (Long/valueOf ^String (:internalDate msg)))]
            (println msg)
            (future
              (Thread/sleep 2000)
              (mc/save db "mails"
                       (assoc
                         msg
                         :device-name device-name
                         :time time
                         :peak? (peak-detection time)
                         :_id (:id msg)))
              )
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
  (let [{:keys [start end]} (:params request)
        start (if start (c/from-long (Long/valueOf start))
                        (t/minus (t/now) (t/minutes 10)))
        end (if end (c/from-long (Long/valueOf end))
                    (t/plus start (t/minutes 10)))
        ]
    {:body (generate-string
             (merge
               {:mail (->>
                        (query/with-collection db "mails"
                                               (query/find {:time {:$gte start :$lte end}})
                                               (query/sort (array-map :time 1))
                                               )
                            (map #(dissoc % :_id))
                           )

                }
               (->>
                 (query/with-collection db "readings"
                                        (query/find {:time {:$gte start :$lte end}})
                                        (query/sort (array-map :time 1))
                                        )
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



(comment
  (doseq [line (drop 1 (clojure.string/split (slurp "email.csv") #"\n"))]
    (let [[dt time value] (clojure.string/split line #",")
          dt (c/from-string (str "2015-11-17T" time "-05:00"))
          ]
      (mc/insert db "readings"
                 {:time dt
                  :type "gsr"
                  :reading (Double/valueOf value)
                  :device-name "test"
                  :fake? true}
                 )

      )
    )
  (mc/save db "mails"
           {:type "mail"
            :device-name "test"
            :time (c/from-string "2015-11-17T12:35:21-05:00")
            :peak? true
            :payload {:headers [{:name "Subject" :value "Update"}
                                {:name "To" :value "Adam@ee.technion.ac.il"}
                                ]}
            :snippet "Hi Professor Shwartz,\n\nI just wanted to update you: \n\n- I sent a revised version of my seminar paper last Sunday to Professor Zaks, and I'm just waiting to hear back from him. "
            :_id "fake"
            :fake? true}
           ))