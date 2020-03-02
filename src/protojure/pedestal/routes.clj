;; Copyright © 2019 State Street Bank and Trust Company.  All rights reserved
;;
;; SPDX-License-Identifier: Apache-2.0

(ns protojure.pedestal.routes
  "Utilities for generating GRPC endpoints as [Pedestal Routes](http://pedestal.io/guides/defining-routes)"
  (:require [protojure.pedestal.interceptors.grpc :as grpc]
            [protojure.pedestal.interceptors.grpc-web :as grpc.web]
            [io.pedestal.interceptor.helpers :as pedestal]))

(set! *warn-on-reflection* true)

(defn- consv
  "identical to (cons), except returns a vector"
  [& args]
  (vec (apply cons args)))

(defn ->tablesyntax
  "Generates routes in [Table Syntax](http://pedestal.io/reference/table-syntax) format"
  [{:keys [rpc-metadata interceptors callback-context] :as options}]
  (for [{:keys [pkg service method method-fn] :as rpc} rpc-metadata]
    (let [fqs (str pkg "." service)
          name (keyword fqs (str method "-handler"))
          handler (pedestal/handler name (partial method-fn callback-context))]
      [(str "/" fqs "/" method)
       :post (-> (consv grpc/error-interceptor interceptors)
                 (conj grpc.web/proxy
                       (grpc/route-interceptor rpc)
                       handler))
       :route-name name])))