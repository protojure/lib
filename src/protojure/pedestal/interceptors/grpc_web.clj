;; Copyright © 2019 State Street Bank and Trust Company.  All rights reserved
;;
;; SPDX-License-Identifier: Apache-2.0

(ns protojure.pedestal.interceptors.grpc-web
  "A [Pedestal](http://pedestal.io/) [interceptor](http://pedestal.io/reference/interceptors) for the [GRPC-WEB](https://github.com/grpc/grpc/blob/master/doc/PROTOCOL-WEB.md) protocol"
  (:require [io.pedestal.interceptor :refer [->Interceptor]])
  (:import (org.apache.commons.codec.binary Base64InputStream))
  (:refer-clojure :exclude [proxy]))

(defn- decode-body
  [{:keys [body] :as request}]
  (assoc request :body (Base64InputStream. body)))

(def ^{:no-doc true :const true} content-types
  #{"application/grpc-web-text"
    "application/grpc-web-text+proto"})

(defn- web-text?
  [{{:strs [content-type]} :headers}]
  (contains? content-types content-type))

(defn- pred->
  "Threads 'item' through both the predicate and, when 'pred' evaluates true, 'xform' functions. Else, just returns 'item'"
  [item pred xform]
  (cond-> item (pred item) xform))

(defn- enter-handler
  [{:keys [request] :as ctx}]
  (assoc ctx :request (pred-> request web-text? decode-body)))

(defn- leave-handler
  [ctx]
  ;; TODO "Clarify & implement grpc-web trailer behavior"
  ctx)

(defn- exception-handler
  [ctx e]
  (assoc ctx :io.pedestal.interceptor.chain/error e))

(def proxy
  "Interceptor that provides a transparent proxy for the [GRPC-WEB](https://github.com/grpc/grpc/blob/master/doc/PROTOCOL-WEB.md) protocol to standard protojure grpc protocol"
  (->Interceptor ::proxy enter-handler leave-handler exception-handler))
