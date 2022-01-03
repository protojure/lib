;; Copyright © 2019 State Street Bank and Trust Company.  All rights reserved
;; Copyright © 2020 Manetu, Inc.  All rights reserved
;;
;; SPDX-License-Identifier: Apache-2.0

(ns protojure.internal.grpc.client.providers.http2.core
  (:require [clojure.core.async :refer [>!! <!! <! >! go go-loop onto-chan] :as async]
            [clojure.tools.logging :as log]
            [protojure.grpc.client.api :as api]
            [protojure.grpc.codec.lpm :as lpm]
            [protojure.internal.grpc.client.providers.http2.jetty :as jetty]
            [promesa.core :as p]
            [promesa.exec :as exec])
  (:import (org.eclipse.jetty.http2.api Stream)
           (java.util.concurrent Executors))
  (:refer-clojure :exclude [resolve]))

(set! *warn-on-reflection* true)

(defn- input-pipeline
  "'inputs' to the GRPC function, e.g. parameters, are LPM encoded in the request-body"
  [{:keys [f] :as input} codecs content-coding max-frame-size]
  (when (some? input)
    (let [input-ch (:ch input)
          output-ch (async/chan 16)]
      (lpm/encode f input-ch output-ch {:codecs codecs :content-coding content-coding :max-frame-size max-frame-size})
      output-ch)))

(defn- codecs-to-accept [codecs]
  (clojure.string/join "," (cons "identity" (keys codecs))))

(defn- send-request
  "Sends an HTTP2 based POST request that adheres to the GRPC-HTTP2 specification"
  [context uri codecs content-coding conn-metadata {:keys [metadata service method options] :as params} input-ch meta-ch output-ch]
  (log/trace (str "Invoking GRPC \""  service "/" method "\""))
  (let [hdrs (-> {"content-type" "application/grpc+proto"
                  "grpc-encoding" (or content-coding "identity")
                  "grpc-accept-encoding" (codecs-to-accept codecs)}
                 (merge conn-metadata metadata))
        url (str uri "/" service "/" method)]
    (jetty/send-request context {:method    "POST"
                                 :url       url
                                 :headers   hdrs
                                 :input-ch  input-ch
                                 :meta-ch   meta-ch
                                 :output-ch output-ch})))

(defn- receive-headers
  "Listen on the metadata channel _until_ we receive a status code.  We are interested in both
  ensuring the call was successful (e.g. :status == 200) and we want to know what :content-coding
  may be applied to any response-body LPM protobufs.  Therefore, we must gate any further
  processing until we have received the \"headers\", and we assume we have fully received them
  once we see the :status tag.  We also note that the metadata channel is not expected to close
  before :status has been received, and nor do we expect it to close even after we've received
  :status since we will presumably be receiving trailers in the future.  Therefore, we treat
  core.async channel closure as an error, and terminate the processing once the response contains
  the :status code.
  "
  [meta-ch]
  (p/create
   (fn [resolve reject]
     (go-loop [response {}]
       (if-let [data (<! meta-ch)]
         (let [response (merge response data)]
           (if (contains? response :status)
             (resolve response)
             (recur response)))
         (reject (ex-info "Unexpected disconnect receiving response" {:response response})))))))

(defn- receive-trailers
  "Drains all remaining metadata, which should primarily consist of :trailer tags, such as
  :grpc-status.  We are considered complete when the jetty layer closes the channel"
  [meta-ch response]
  (p/create
   (fn [resolve reject]
     (go-loop [response response]
       (if-let [{:keys [trailers] :as data} (<! meta-ch)]
         (recur (update response :headers (fn [headers] (merge headers trailers))))
         (resolve response))))))

(defn- receive-body
  "Receives LPM encoded payload based on the :content-coding header when an input-channel is provided"
  [codecs input-ch {:keys [f] :as output} {{:strs [grpc-encoding]} :headers :as response}]
  (if (some? input-ch)
    (let [output-ch (:ch output)]
      (lpm/decode f input-ch output-ch {:codecs codecs :content-coding grpc-encoding}))
    (p/resolved true)))

(defn- safe-close [ch]
  (when ch
    (log/debug "closing output")
    (async/close! ch)))

(defn- ->status-code [status]
  (if (some? status)
    (Integer/parseInt status)
    2))

(defn- decode-grpc-status [{:strs [grpc-status grpc-message]}]
  (let [grpc-status (->status-code grpc-status)]
    (cond-> {:status grpc-status}
      (some? grpc-message) (assoc :message grpc-message))))

(defn- receive-payload
  "Handles all remaining response payload, which consists of both response body and trailers.
  We process them in parallel since we can't be sure that the server won't interleave HEADER
  and DATA frames, even though we don't expect this to be a normal ordering.  We _could_
  probably get away with draining the queues serially (data-ch and then meta-ch) but we would
  run the risk of stalling the pipeline if the meta-ch were to fill"
  [codecs meta-ch data-ch output {:keys [status] :as response}]
  (if (-> status (= 200))
    (-> (p/all [(receive-body codecs data-ch output response)
                (receive-trailers meta-ch response)])
        (p/then (fn [[_ {:keys [headers] :as response}]] ;; [body-response trailers-response]
                  (let [{:keys [status] :as resp} (decode-grpc-status headers)]
                    (if (zero? status)
                      resp
                      (p/rejected (ex-info "bad grpc-status response" (assoc resp :meta {:response response}))))))))
    (p/rejected (ex-info "bad status response" {:response response}))))

(defn- client-send [input-ch stream]
  (jetty/transmit-data-frames input-ch stream))

(defn- client-receive [meta-ch codecs output-ch output]
  (-> (receive-headers meta-ch)
      (p/then (partial receive-payload codecs meta-ch output-ch output))))

(defn- safe-close! [ch]
  (some-> ch async/close!))

(def #^{:private true} executor (Executors/newCachedThreadPool))

;;-----------------------------------------------------------------------------
;;-----------------------------------------------------------------------------
;; External API
;;-----------------------------------------------------------------------------
;;-----------------------------------------------------------------------------

;;-----------------------------------------------------------------------------
;; Provider
;;-----------------------------------------------------------------------------
(deftype Http2Provider [context uri codecs content-coding max-frame-size input-buffer-size metadata]
  api/Provider

  (invoke [_ {:keys [input output] :as params}]
    (let [input-ch (input-pipeline input codecs content-coding max-frame-size)
          meta-ch (async/chan 32)
          output-ch (when (some? output) (async/chan (max 32
                                                          (/ input-buffer-size max-frame-size))))]
      (-> (send-request context uri codecs content-coding metadata params input-ch meta-ch output-ch)
          (p/then (fn [^Stream stream]
                    (p/all [(-> (client-send input-ch stream)
                                (p/catch (fn [ex]
                                           (throw ex))))
                            (-> (client-receive meta-ch codecs output-ch output)
                                (p/catch (fn [ex]
                                           (safe-close! output-ch)
                                           (async/close! meta-ch)
                                           (safe-close! input-ch)
                                           (throw ex))))])))
          (p/then (fn [[_ status]]
                    (log/trace "GRPC completed:" status)
                    status)
                  executor)
          (p/catch (fn [ex]
                     (log/error "GRPC failed:" ex)
                     (safe-close (:ch output))
                     (throw ex))))))

  (disconnect [_]
    (jetty/disconnect context)))
