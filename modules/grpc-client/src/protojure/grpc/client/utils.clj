;; Copyright © 2019 State Street Bank and Trust Company.  All rights reserved
;; Copyright © 2019-2022 Manetu, Inc.  All rights reserved
;;
;; SPDX-License-Identifier: Apache-2.0

(ns protojure.grpc.client.utils
  "Functions used for grpc unary calls in clients generated by protojure-protoc-plugin"
  (:require [promesa.core :as p]
            [protojure.grpc.client.api :as grpc]
            [clojure.core.async :as async])
  (:refer-clojure :exclude [take]))

(set! *warn-on-reflection* true)

(defn- take [ch]
  (p/create
   (fn [resolve reject]
     (async/take! ch resolve))))

(defn- put [ch val]
  (p/create
   (fn [resolve reject]
     (if (some? val)
       (async/put! ch val resolve)
       (resolve true)))))

(defn send-unary-params
  "
Places an item on a channel and then closes the channel, returning a promise that completes
after the channel is closed.  Used in remote procedure calls with unary parameters.

#### Parameters

| Value       | Type                 | Description                                                                |
|-------------|----------------------|----------------------------------------------------------------------------|
| **ch**      | _core.async/channel_ | A core.async channel expected to carry 'params' and be subsequently closed |
| **params**  | _any_                | The object to place on the channel                                         |
  "
  [ch params]
  (-> (put ch params)
      (p/then (fn [_] (async/close! ch)))))

(defn invoke-unary
  "
Invokes a GRPC operation similar to the invoke operation within [[api/Provider]], but the promise returned
resolves to a decoded result when successful.  Used in remote procedure calls with unary return types.

#### Parameters

| Value       | Type                 | Description                                                                |
|-------------|----------------------|----------------------------------------------------------------------------|
| **client**  | _[[api/Provider]]_   | An instance of a client provider                                           |
| **params**  | _map_                | See 'params' in the '(invoke ..)' method within [[api/Provider]]           |
| **ch**      | _core.async/channel_ | A core.async channel expected to carry the response data                   |
  "
  [client params ch]
  (-> (grpc/invoke client (assoc params :unary? true))
      (p/then (fn [_] (take ch)))))