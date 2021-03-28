;; Copyright © 2019 State Street Bank and Trust Company.  All rights reserved
;;
;; SPDX-License-Identifier: Apache-2.0

(ns protojure.internal.grpc.client.providers.http2.jetty
  (:require [promesa.core :as p]
            [clojure.core.async :refer [>!! <!! <! >! go go-loop] :as async]
            [clojure.tools.logging :as log])
  (:import (java.net InetSocketAddress)
           (java.nio ByteBuffer)
           (org.eclipse.jetty.http2.client HTTP2Client)
           (org.eclipse.jetty.http2.api Stream$Listener
                                        Stream
                                        Session)
           (org.eclipse.jetty.http2.api.server ServerSessionListener$Adapter)
           (org.eclipse.jetty.http2.frames HeadersFrame
                                           DataFrame)
           (org.eclipse.jetty.util Promise Callback)
           (org.eclipse.jetty.http HttpFields
                                   HttpField
                                   HttpURI
                                   HttpVersion
                                   MetaData$Request
                                   MetaData$Response
                                   MetaData)
           (org.eclipse.jetty.util.ssl SslContextFactory SslContextFactory$Client))
  (:refer-clojure :exclude [resolve]))

(set! *warn-on-reflection* true)

;;------------------------------------------------------------------------------------
;; Utility functions
;;------------------------------------------------------------------------------------

(defn- jetty-promise
  "converts a jetty promise to promesa"
  [f]
  (p/promise
   (fn [resolve reject]
     (let [p (reify Promise
               (succeeded [_ result]
                 (resolve result))
               (failed [_ error]
                 (reject error)))]
       (f p)))))

(defn- jetty-callback-promise
  "converts a jetty 'callback' to promesa"
  [f]
  (p/promise
   (fn [resolve reject]
     (let [cb (reify Callback
                (succeeded [_]
                  (resolve true))
                (failed [_ error]
                  (reject error)))]
       (f cb)))))

(defn- ->fields
  "converts a map of [string string] name/value attributes to a jetty HttpFields container"
  [headers]
  (let [fields (new HttpFields)]
    (run! (fn [[k v]] (.put fields ^String k ^String v)) headers)
    fields))

(defn- fields->
  "converts jetty HttpFields container to a [string string] map"
  [^HttpFields fields]
  (->> (.iterator fields)
       (iterator-seq)
       (reduce (fn [acc ^HttpField x]
                 (assoc acc (.getName x) (.getValue x))) {})))

(defn- build-request
  "Builds a HEADERFRAME representing our request"
  [{:keys [method headers url] :or {method "GET" headers {}} :as request} last?]
  (log/trace "Sending request:" request "ENDFRAME=" last?)
  (let [_uri (HttpURI. ^String url)]
    (as-> (->fields headers) $
      (MetaData$Request. method _uri HttpVersion/HTTP_2 $)
      (HeadersFrame. $ nil last?))))

(defn- close-all! [& channels]
  (run! (fn [ch] (when (some? ch) (async/close! ch))) channels))

(defn- stream-log [sev ^Stream stream & msg]
  (log/log sev (apply str (cons (str "STREAM " (.getId stream) ": ") msg))))

(defn- receive-listener
  "Implements a org.eclipse.jetty.http2.api.Stream.Listener set of callbacks"
  [meta-ch data-ch]
  (let [end-stream! (fn [stream] (stream-log :trace stream "Closing") (close-all! meta-ch data-ch))]
    (reify Stream$Listener
      (onHeaders [_ stream frame]
        (let [^MetaData metadata (.getMetaData ^HeadersFrame frame)
              fields (fields-> (.getFields metadata))
              data (if (.isResponse metadata)
                     (let [status (.getStatus ^MetaData$Response metadata)
                           reason (.getReason ^MetaData$Response metadata)]
                       (cond-> {:headers fields}
                         (some? status) (assoc :status status)
                         (some? reason) (assoc :reason reason)))
                     {:trailers fields})
              last? (.isEndStream ^HeadersFrame frame)]
          (stream-log :trace stream "Received HEADER-FRAME: " data " ENDFRAME=" last?)
          (>!! meta-ch data)
          (when last?
            (end-stream! stream))))
      (onData [_ stream frame callback]
        (let [data (.getData ^DataFrame frame)
              len (.remaining data)
              last? (.isEndStream ^DataFrame frame)]
          (stream-log :trace stream "Received DATA-FRAME (" len " bytes) ENDFRAME=" last?)
          (when (some? data-ch)
            (doseq [b (repeatedly len #(.get data))]
              (async/>!! data-ch (bit-and 0xff b)))) ;; FIXME: cast to byte?
          (when last?
            (end-stream! stream)))
        (.succeeded callback))
      (onFailure [_ stream error reason callback]
        (stream-log :error stream "FAILURE: " error)
        (>!! meta-ch {:error {:type :failure :code error :reason reason}})
        (end-stream! stream)
        (.succeeded callback))
      (onReset [_ stream frame]
        (stream-log :error stream "Received RST-FRAME")
        (let [error (.getError frame)]
          (>!! meta-ch {:error {:type :reset :code error}})
          (end-stream! stream)))
      (onIdleTimeout [_ stream ex]
        (stream-log :error stream "Timeout")
        (>!! meta-ch {:error {:type :timeout :error ex}})
        (end-stream! stream))
      (onClosed [_ stream]
        (stream-log :trace stream "Closed"))
      (onPush [_ stream frame]
        (stream-log :trace stream "Received PUSH-FRAME")))))

(defn- transmit-data-frame
  "Transmits a single DATA frame"
  ([stream data]
   (transmit-data-frame stream data false 0))
  ([^Stream stream data last? padding]
   (stream-log :trace stream "Sending DATA-FRAME with " (count data) " bytes, ENDFRAME=" last?)
   @(jetty-callback-promise
     (fn [cb]
       (let [frame (DataFrame. (.getId stream) (ByteBuffer/wrap data) last? padding)]
         (.data stream frame cb))))))

(defn- transmit-eof
  "Transmits an empty DATA frame with the ENDSTREAM flag set to true, signifying the end of stream"
  [stream]
  (transmit-data-frame stream (byte-array 0) true 0))

(defn transmit-data-frames
  "Creates DATA frames from the buffers on the channel"
  [input stream]
  (if (some? input)
    (p/promise
     (fn [resolve reject]
       (go-loop []
         (if-let [frame (<! input)]
           (do
             (try
               (transmit-data-frame stream frame)
               (catch Exception e
                 (reject e)))
             (recur))
           (try
             (resolve (transmit-eof stream))
             (catch Exception e
               (reject e)))))))
    (p/resolved true)))

;;------------------------------------------------------------------------------------
;; Exposed API
;;------------------------------------------------------------------------------------

(defn connect [{:keys [host port input-buffer-size idle-timeout ssl] :or {host "localhost" port 80 input-buffer-size 16384 ssl false} :as params}]
  (let [client (HTTP2Client.)
        address (InetSocketAddress. ^String host (int port))
        listener (ServerSessionListener$Adapter.)
        ssl-context-factory (when ssl (SslContextFactory$Client.))]
    (when ssl (.addBean client ssl-context-factory))
    (log/debug "Connecting with parameters: " params)
    (.setInputBufferSize client input-buffer-size)
    (.setInitialStreamRecvWindow client input-buffer-size)
    (when idle-timeout
      (.setIdleTimeout client idle-timeout))
    (.start client)
    (-> (jetty-promise
         (fn [p]
           (.connect client (when ssl ssl-context-factory) address listener p)))
        (p/then (fn [session]
                  (let [context {:client client :session session}]
                    (log/debug "Session established:" context)
                    context)))
        (p/catch (fn [e]
                   (.stop client)
                   (throw e))))))

(defn send-request
  [{:keys [^Session session] :as context}
   {:keys [input-ch meta-ch output-ch] :as request}]
  (let [request-frame (build-request request (nil? input-ch))
        listener (receive-listener meta-ch output-ch)]
    (-> (jetty-promise
         (fn [p]
           (.newStream session request-frame p listener)))
        (p/catch (fn [ex]
                   (close-all! meta-ch output-ch)
                   (throw ex))))))

(defn disconnect [{:keys [^HTTP2Client client] :as context}]
  (log/debug "Disconnecting:" context)
  (.stop client)
  (dissoc context :client :session))
