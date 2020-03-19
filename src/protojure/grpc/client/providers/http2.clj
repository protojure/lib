;; Copyright © 2019 State Street Bank and Trust Company.  All rights reserved
;; Copyright © 2020 Manetu, Inc.  All rights reserved
;;
;; SPDX-License-Identifier: Apache-2.0

(ns protojure.grpc.client.providers.http2
  "Implements the [GRPC-HTTP2](https://github.com/grpc/grpc/blob/master/doc/PROTOCOL-HTTP2.md) protocol for clients"
  (:require [protojure.internal.grpc.client.providers.http2.core :as core]
            [protojure.internal.grpc.client.providers.http2.jetty :as jetty]
            [protojure.grpc.codec.compression :refer [builtin-codecs]]
            [promesa.core :as p]
            [lambdaisland.uri :as lambdaisland]
            [clojure.tools.logging :as log]))

(set! *warn-on-reflection* true)

(defn connect
  "
Connects the client to a [GRPC-HTTP2](https://github.com/grpc/grpc/blob/master/doc/PROTOCOL-HTTP2.md) compatible server

#### Parameters
A map with the following entries:

| Value                 | Type     | Default | Description                                                               |
|-----------------------|----------|-------------------------------------------------------------------------------------|
| **uri**               | _String_ | n/a     | The URI of the GRPC server                                                |
| **codecs**            | _map_    | [[protojure.grpc.codec.core/builtin-codecs]] | Optional custom codecs               |
| **content-coding**    | _String_ | nil     | The encoding to use on request data                                       |
| **max-frame-size**    | _UInt32_ | 16384   | The maximum HTTP2 DATA frame size                                         |
| **input-buffer-size** | _UInt32_ | 16M     | The input-buffer size                                                     |
| **metadata**          | _map_    | n/a     | Optional [string string] tuples that will be submitted as attributes to the request, such as via HTTP headers for GRPC-HTTP2 |

#### Return value
A promise that, on success, evaluates to an instance of [[api/Provider]].
_(api/disconnect)_ should be used to release any resources when the connection is no longer required.
  "
  [{:keys [uri codecs content-coding max-frame-size input-buffer-size metadata] :or {codecs builtin-codecs max-frame-size 16384 input-buffer-size 16384} :as params}]
  (log/debug "Connecting with GRPC-HTTP2:" params)
  (let [{:keys [host port]} (lambdaisland/uri uri)]
    (-> (jetty/connect {:host host :port (Integer/parseInt port) :input-buffer-size input-buffer-size})
        (p/then #(core/->Http2Provider % uri codecs content-coding max-frame-size input-buffer-size metadata)))))
