;; Copyright © 2019 State Street Bank and Trust Company.  All rights reserved
;;
;; SPDX-License-Identifier: Apache-2.0

(ns protojure.pedestal.interceptors.grpc-web
  "A [Pedestal](http://pedestal.io/) [interceptor](http://pedestal.io/reference/interceptors) for the [GRPC-WEB](https://github.com/grpc/grpc/blob/master/doc/PROTOCOL-WEB.md) protocol"
  (:require [io.pedestal.interceptor :refer [->Interceptor]]
            [clojure.core.async :as async]
            [clojure.data]
            [promesa.core :as p]
            [clojure.tools.logging :as log])
  (:refer-clojure :exclude [proxy])
  (:import (org.apache.commons.codec.binary Base64InputStream Base64OutputStream)
           (java.io PipedOutputStream PipedInputStream)))

(set! *warn-on-reflection* true)

(defn read-n [from-chan n]
  "Convenience method for consuming n [n] or less values from a channel [from-chan]"
  (async/go-loop [res []]
    (let [v (async/<! from-chan)]
      (if (nil? v)
        [true res]
        (let [new-res (conj res v)]
          (if (= n (count new-res))
            [false new-res]
            (recur new-res)))))))

(defn- decode-body
  "Consumes 4 base64 encoded bytes from the body, and writes to a new channel that replaces the prior :body-ch on the
  request. Note that any consumption from the :body inputstream that is not followed by a write to the :body-ch key
  results in that data being lost to the grpc interceptor :grpc-params/:body-ch key."
  [{:keys [body-ch] :as request}]
  (let [dec-ch (async/chan 4056)
        decoder (java.util.Base64/getDecoder)]
    (let [b64-decode-error-promise
          (p/promise (fn [resolve reject]
                       (when-not (instance? clojure.core.async.impl.channels.ManyToManyChannel body-ch)
                         (log/error "grpc-web/proxy did not receive an appropriate body-ch")
                         (async/close! dec-ch)
                         (log/error "grpc-web interceptor encountered an unexpected body type on-leave")
                         (resolve (ex-info "grpc-web interceptor encountered an unexpected body type on-leave"
                                           {:causes          #{:incompatible-body-ch-value-type}
                                            :body-ch-value-type (type body-ch)})))
                       (async/go-loop [[final encoded] (async/<! (read-n body-ch 4))]
                         (if (and (empty? encoded) final)
                           (do
                             (resolve nil)
                             (async/close! dec-ch))
                           (do
                             (try
                               (doseq [b (.decode decoder (byte-array encoded))]
                                 (async/>! dec-ch b))
                               (catch Exception e
                                 (async/close! dec-ch)
                                 (resolve e)))
                             (recur (async/<! (read-n body-ch 4))))))))]
      (-> (assoc request :body-ch dec-ch)
          (assoc :b64-decode-error-promise b64-decode-error-promise)))))

(defn- num->bytes
  "Serializes an integer to a byte-array."
  [num]
  (byte-array (for [i (range 4)]
                (-> (unsigned-bit-shift-right num
                                              (* 8 (- 4 i 1)))
                    (bit-and 0x0FF)))))

(defn- make-grpc-web-trailers-string [trailers]
  (reduce (fn [s [k v]]
            (str s k ":" v "\r\n")) "" trailers))

(defn- make-grpc-web-trailers-frame [trailers]
  "This is the lightly documented handling of trailers from
  https://github.com/grpc/grpc/blob/master/doc/PROTOCOL-WEB.md#protocol-differences-vs-grpc-over-http2
  See section beginning `Message framing (vs. http2-transport-mapping`"
  (let [trailer-bytes (.getBytes ^String (make-grpc-web-trailers-string trailers))]
    (byte-array
     (concat
      [0x80]
      (into [] (num->bytes (count trailer-bytes)))
      (into [] trailer-bytes)))))

(defn- generate-trailers
  [b64-ex]
  (let [b64-ex (bean b64-ex)
        {:keys [grpc-status grpc-message]} (cond (=
                                                  (:message b64-ex)
                                                  "Input byte[] should at least have 2 bytes for base64 bytes")
                                                 {:grpc-status 3 :grpc-message "Bad Base64 Encoded Request"}
                                                 true {:grpc-status 13 :grpc-message "Internal Error"})]
    (-> {"grpc-status" grpc-status}
        (cond-> (some? grpc-message) (assoc "grpc-message" grpc-message)))))

(defmulti encode-web-body "Writes trailers to body per grpc-web specification"
  (fn [x] (type (-> x :response :body))))

(defmethod encode-web-body clojure.core.async.impl.channels.ManyToManyChannel
  [{{:keys [body trailers]} :response :as ctx}]
  (let [body-w-trailers (async/chan 256)]
    (async/go-loop [s (async/<! body)]
      (if (not s)
       ;;TODO Note blocking on this promise only works because grpc-web only supports server-side streaming -- e.g.,
       ;; we can count on the request body decode having consumed all bytes prior to responding in the grpc-web-text
       ;; case
        (let [trailers (async/<! trailers)
              frame (make-grpc-web-trailers-frame trailers)]
         ;;Write trailer frame
          (async/>! body-w-trailers ^bytes frame)
          (async/close! body-w-trailers))
        (do
          (async/>! body-w-trailers ^bytes s)
          (recur (async/<! body)))))
    (-> (assoc-in ctx [:response :body] body-w-trailers)
        (update-in [:response :headers] #(merge % {"Content-Type" "application/grpc-web+proto"})))))

(defmethod encode-web-body nil
  [{{:keys [trailers] :as response} :response :as ctx}]
  ;;TODO Note blocking on this promise only works because grpc-web only supports server-side streaming -- e.g.,
  ;; we can count on the request body decode having consumed all bytes prior to responding in the grpc-web-text
  ;; case
  (let [body-w-trailers (async/chan 256)]
    ;;Write trailer frame
    (async/go []
              (let [frame (make-grpc-web-trailers-frame trailers)]
                (async/>! body-w-trailers ^bytes frame))
              (async/close! body-w-trailers))
    (-> (assoc-in ctx [:response :body] body-w-trailers)
        (update-in [:response :headers] #(merge % {"Content-Type" "application/grpc-web+proto"})))))

(defmethod encode-web-body :default
  [{{:keys [body] :as response} :response :as ctx}]
  (throw (ex-info "grpc-web interceptor encountered an unexpected body type on-leave"
                  {:causes             #{:incompatible-body-value-type}
                   :body-value-type (type body)})))

(defmulti encode-web-text-body "Consumes bytes from the response body and base64 encodes the payload"
  (fn [x] (type (-> x :response :body))))

(defmethod encode-web-text-body clojure.core.async.impl.channels.ManyToManyChannel
  [{{:keys [body trailers]} :response {:keys [b64-decode-error-promise]} :request :as ctx}]
  (let [pos (PipedOutputStream.)
        pis (PipedInputStream. pos)
        ;; N.B. passing a string instead of nil in the last position (the line end) caused no data to send
        b64-is (Base64InputStream. pis true -1 nil)]
    (async/go-loop [s (async/<! body)]
      (if (not s)
        ;;TODO Note blocking on this promise only works because grpc-web only supports server-side streaming -- e.g.,
        ;; we can count on the request body decode having consumed all bytes prior to responding in the grpc-web-text
        ;; case
        (let [b64-ex @b64-decode-error-promise
              trailers (async/<! trailers)
              frame (make-grpc-web-trailers-frame (if b64-ex
                                                    (generate-trailers b64-ex)
                                                    trailers))]
          ;;Write trailer frame
          (.write pos ^bytes frame)
          (.flush pos)
          (.close pos))
        (do
          (.write pos ^bytes s)
          (recur (async/<! body)))))
    (-> (assoc-in ctx [:response :body] b64-is)
        (update-in [:response :headers] #(merge % {"Content-Type" "application/grpc-web-text"})))))

(defmethod encode-web-text-body nil
  [{{:keys [trailers] :as response} :response {:keys [b64-decode-error-promise]} :request :as ctx}]
  ;;TODO Note blocking on this promise only works because grpc-web only supports server-side streaming -- e.g.,
  ;; we can count on the request body decode having consumed all bytes prior to responding in the grpc-web-text
  ;; case
  (let [b64-ex @b64-decode-error-promise
        frame (make-grpc-web-trailers-frame (if b64-ex
                                              (generate-trailers b64-ex)
                                              trailers))
        pos (PipedOutputStream.)
        pis (PipedInputStream. pos)
        b64-is (Base64InputStream. pis true -1 nil)]
    ;;Write trailer frame
    (.write pos ^bytes frame)
    (.flush pos)
    (.close pos)
    (-> (assoc-in ctx [:response :body] b64-is)
        (update-in [:response :headers] #(merge % {"Content-Type" "application/grpc-web-text"})))))

(defmethod encode-web-text-body :default
  [{{:keys [body] :as response} :response :as ctx}]
  (throw (ex-info "grpc-web interceptor encountered an unexpected body type on-leave"
                  {:causes             #{:incompatible-body-value-type}
                   :body-value-type (type body)})))

(def ^{:no-doc true :const true} content-types-text
  #{"application/grpc-web-text"})

(def ^{:no-doc true :const true} content-types-web
  #{"application/grpc-web"
    "application/grpc-web+proto"})

(defn- web-text?
  [{{:strs [content-type]} :headers}]
  (contains? content-types-text content-type))

(defn- accept-web-text?
  [{{{:strs [accept]} :headers} :request}]
  (contains? content-types-text accept))

(defn- accept-web?
  "The grpc-web js bindings currently set the `Accept:` header to \"*/*\" which complicates handling trailers. We
  fallback to relying on the content-type to determine a client is likely a browser and requires special response
  content-type handling"
  [{{{:strs [content-type]} :headers} :request}]
  (contains? content-types-web content-type))

(defn- pred->
  "Threads 'item' through both the predicate and, when 'pred' evaluates true, 'xform' functions. Else, just returns 'item'"
  [item pred xform]
  (cond-> item (pred item) xform))

(defn- enter-handler
  [{:keys [request] :as ctx}]
  (assoc ctx :request (pred-> request web-text? decode-body)))

(defn- leave-handler
  [{:keys [response] :as ctx}]
  (-> (pred-> ctx accept-web-text? encode-web-text-body)
      (pred-> accept-web? encode-web-body)))

(defn- exception-handler
  [ctx e]
  (assoc ctx :io.pedestal.interceptor.chain/error e))

(def proxy
  "Interceptor that provides a transparent proxy for the [GRPC-WEB](https://github.com/grpc/grpc/blob/master/doc/PROTOCOL-WEB.md) protocol to standard protojure grpc protocol"
  (->Interceptor ::proxy enter-handler leave-handler exception-handler))

(defn error-leave-handler [{{:keys [grpc-error]} :response :as ctx}]
  (if grpc-error
    (-> (pred-> ctx accept-web-text? encode-web-text-body)
        (pred-> accept-web? encode-web-body))
    ctx))
;;FIXME when HTTP/3 has a grpc specification
;; since we rely on protojure.pedestal.interceptors.grpc/error-interceptor to form the grpc compliant trailers,
;; we expose this error interceptor (and insert in protojure.pedestal.routes/->tablesyntax prior to
;; interceptors.grpc/error-interceptor) so that this interceptor can check for the grpc-web-text accept content type
;; and encode appropriately when an exception is thrown.
;; Once we have a third grpc specification based on transport, better to fix these abstractions such that we have
;; HTTP1.1/HTTP2/HTTP3 based encoding
(def error-interceptor
  "Interceptor that writes grpc exception information in a grpc-web compatible encoding"
  (->Interceptor ::grpc-web-error
                 identity
                 error-leave-handler
                 exception-handler))
