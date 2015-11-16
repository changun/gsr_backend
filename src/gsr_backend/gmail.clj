(ns gsr-backend.gmail
  (:require [org.httpkit.client :as http]
            [ring.util.codec :refer [url-encode form-encode]]
            [cheshire.core :refer :all]
            [monger.joda-time]
            )
  (:import (java.util UUID)
           (java.util.concurrent TimeoutException)
           (java.io IOException)))



(def ^:private client-id "48636836762-ranu2eaj1lirsslj8sb613t8evuh6qeb.apps.googleusercontent.com")
(def ^:private client-secret "BXEcOEQjitK134UyH5AC5xoB")
(def ^:private scope "https://www.googleapis.com/auth/gmail.modify")
(defn get-access-token [code]
  (let [{:keys [error status body]}
        @(http/post "https://www.googleapis.com/oauth2/v3/token"
                    {:form-params {"code"          code
                                   "client_id"     client-id
                                   "scope"         scope
                                   "client_secret" client-secret
                                   ; "redirect_uri"  callback-uri
                                   "grant_type"    "authorization_code"
                                   }}
                    )

        ]
    (println body)
    (if (or error (not= status 200))
      (throw (or error (Exception. status body)))
      (-> body (parse-string true) :access_token)))
  )



(def ^:private gmail-message-api-uri "https://www.googleapis.com/gmail/v1/users/me")
(def ^:private scope "https://www.googleapis.com/auth/gmail.readonly")

(defn- gmail-options
  "merge base options with the given options"
  [access-token options]
  (-> options
      (assoc :timeout 2000)
      (assoc-in [:headers "Authorization"] (str "Bearer " access-token))
      (assoc-in [:headers "Accept-Encoding"] " gzip")
      (assoc-in [:headers "User-Agent"] " my program (gzip)")
      (assoc-in [:query-params :quotaUser] (UUID/randomUUID))
      )
  )
(defn- gmail-endpoint [path options access-token]
  (let [{:keys [error status] :as res}
        @(http/get (str gmail-message-api-uri path)
                   (gmail-options access-token options)
                   )

        ]
    (if (or (= status 429)
            (isa? (class error) TimeoutException)
            (isa? (class error) IOException)
            )
      (recur path options access-token)
      res))
  )

(defn- msg-sequence
  ([filter access-token & [page-token]]
   (println "msg-seq")
   (let [params (cond-> {}
                        filter
                        (assoc :q filter)
                        page-token
                        (assoc :pageToken page-token)
                        )
         {:keys [status body error]} (gmail-endpoint "/messages" {:query-params params} access-token)]
     (if (or error (not= status 200))
       (throw (Throwable. (str "Failed, exception: " error " " status body)))
       (let [{:keys [nextPageToken messages]} (parse-string body true)]
         (if nextPageToken
           (concat messages (lazy-seq (msg-sequence filter access-token nextPageToken)))
           messages
           )
         )
       )))
  )

(defn- get-msg
  ([id access-token]
   (let [{:keys [status body error]} (gmail-endpoint (str "/messages/" id)  {} access-token)]
     (if (or error (not= status 200))
       (throw (Throwable. (str "Failed, exception: " error " " status body)))
       (parse-string body true)
       )))
  )
