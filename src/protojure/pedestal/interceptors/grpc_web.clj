;; Copyright © 2019 State Street Bank and Trust Company.  All rights reserved
;;
;; SPDX-License-Identifier: Apache-2.0

(ns protojure.pedestal.interceptors.grpc-web
  "A [Pedestal](http://pedestal.io/) [interceptor](http://pedestal.io/reference/interceptors) for the [GRPC-WEB](https://github.com/grpc/grpc/blob/master/doc/PROTOCOL-WEB.md) protocol"
  (:require [io.pedestal.interceptor :refer [->Interceptor]]
            [clojure.core.async :as async]
            [clojure.data])
  (:refer-clojure :exclude [proxy]))

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
    (async/go-loop [[final encoded] (async/<! (read-n body-ch 4))]
      (if (and (empty? encoded) final)
        (async/close! dec-ch)
        (do
          (doseq [b (.decode decoder (byte-array encoded))]
            (async/>! dec-ch b))
          (recur (async/<! (read-n body-ch 4))))))
    (assoc request :body-ch dec-ch)))

(defn- encode-body
  "Consumes bytes from the response body channel and base64 encodes the payload"
  [{{:keys [body] :as response} :response :as ctx}]
  (let [encoder (java.util.Base64/getEncoder)
        out-ch (async/chan 4056)]
    (async/go-loop [s (async/<! body)]
      (if (not s)
        (async/close! out-ch)
        (do
          (async/>! out-ch (.encode encoder ^bytes s))
          (recur (async/<! body)))))
    (-> (assoc-in ctx [:response :body] out-ch)
        (update-in [:response :headers] #(merge % {"Content-Type" "application/grpc-web-text"})))))

(def ^{:no-doc true :const true} content-types
  #{"application/grpc-web-text"})

(defn- web-text?
  [{{:strs [content-type]} :headers}]
  (contains? content-types content-type))

(defn- accept-web-text?
  [{{{:strs [accept]} :headers} :request}]
  (contains? content-types accept))

(defn- pred->
  "Threads 'item' through both the predicate and, when 'pred' evaluates true, 'xform' functions. Else, just returns 'item'"
  [item pred xform]
  (cond-> item (pred item) xform))

(defn- enter-handler
  [{:keys [request] :as ctx}]
  (assoc ctx :request (pred-> request web-text? decode-body)))

(defn- leave-handler
  [{:keys [response] :as ctx}]
  ;; TODO "Clarify & implement grpc-web trailer behavior"
  (pred-> ctx accept-web-text? encode-body))

(defn- exception-handler
  [ctx e]
  (assoc ctx :io.pedestal.interceptor.chain/error e))

(def proxy
  "Interceptor that provides a transparent proxy for the [GRPC-WEB](https://github.com/grpc/grpc/blob/master/doc/PROTOCOL-WEB.md) protocol to standard protojure grpc protocol"
  (->Interceptor ::proxy enter-handler leave-handler exception-handler))
