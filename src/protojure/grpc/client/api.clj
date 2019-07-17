;; Copyright © 2019 State Street Bank and Trust Company.  All rights reserved
;;
;; SPDX-License-Identifier: Apache-2.0

(ns protojure.grpc.client.api
  "Provider independent client API for invoking GRPC requests")

(defprotocol Provider
  ;;;;----------------------------------------------------------------------------------------------------
  (invoke [this params]
    "
Invokes a GRPC-based remote-procedure against the provider

#### Parameters
A map with the following entries:

| Value           | Type              | Description                                                               |
|-----------------|-------------------|---------------------------------------------------------------------------|
| **service**     | _String_          | The GRPC service-name of the endpoint                                     |
| **method**      | _String_          | The GRPC method-name of the endpoint                                      |
| **metadata**    | _map_             | Optional [string string] tuples that will be submitted as attributes to the request, such as via HTTP headers for GRPC-HTTP2 |
| **input**       | _map_             | See _Input_ section below                                                 |
| **output**      | _map_             | See _Output_ section below                                                |

##### Unary vs Streaming Input

Any [GRPC Service endpoint](https://grpc.io/docs/guides/concepts.html#service-definition) can define methods that take either unary or streaming inputs or outputs.
This API assumes core.async channels in either case as a 'streaming first' design.  For unary input, simply produce one message before closing the stream.  Closing the stream indicates that the input is complete.

##### Input
The _input_ parameter is a map with the following fields:

| Value           | Type              | Description                                                               |
|-----------------|-------------------|---------------------------------------------------------------------------|
| **f**           | _(fn [map])_      | A protobuf new-XX function, such as produced by the protoc-gen-clojure compiler, to be applied to any outbound request messages |
| **ch**          | _core.async/chan_ | A core.async channel used to send input parameters.  Close to complete. |

##### Output
The _output_ parameter is a map with the following fields:

| Value           | Type              | Description                                                               |
|-----------------|-------------------|---------------------------------------------------------------------------|
| **f**           | _(fn [is])_       | A protobuf pb->msg function, such as produced by the protoc-gen-clojure compiler, to be applied to any incoming response messages |
| **ch**          | _core.async/chan_ | A core.async channel that will be populated with any GRPC return messages.  Unary responses arrive as a single message on the channel.  Will close when the response is complete. |

#### Return value
A promise that, on success, evaluates to a map with the following entries:

| Value           | Type     | Description                                                               |
|-----------------|----------|---------------------------------------------------------------------------|
| **status**      | _Int_    | The [GRPC status code](https://github.com/grpc/grpc/blob/master/doc/statuscodes.md) returned from the remote procedure                   |
| **message**     | _String_ | The GRPC message (if any) returned from the remote procedure              |

#### Example

```
(let [{:keys [status message]} @(invoke client {:service \"my.service\"
                                                :method \"MyMethod\"
                                                :input {:f myservice/new-MyRequest :ch input}
                                                :output {:f myservice/pb->MyResponse :ch output}})]
     (println \"status:\" status))
```

")

  ;;;;----------------------------------------------------------------------------------------------------
  (disconnect [this]
    "Disconnects from the GRPC endpoint and releases all resources held by the underlying provider"))