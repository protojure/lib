;; Copyright © 2019 State Street Bank and Trust Company.  All rights reserved
;; Copyright © 2019-2022 Manetu, Inc.  All rights reserved
;;
;; SPDX-License-Identifier: Apache-2.0

(ns protojure.grpc.codec.lpm
  "Utility functions for GRPC [length-prefixed-message](https://github.com/grpc/grpc/blob/master/doc/PROTOCOL-HTTP2.md#requests) encoding."
  (:require [clojure.core.async :refer [<!! >!!] :as async]
            [promesa.core :as p]
            [promesa.exec :as p.exec]
            [protojure.protobuf :refer [->pb]]
            [protojure.grpc.codec.compression :as compression]
            [protojure.internal.io :as pio]
            [clojure.tools.logging :as log]
            [clojure.java.io :as io])
  (:import (java.io InputStream OutputStream ByteArrayOutputStream)
           (org.apache.commons.io.input BoundedInputStream))
  (:refer-clojure :exclude [resolve]))

(set! *warn-on-reflection* true)

(def lpm-thread-executor (if p.exec/vthreads-supported? p.exec/vthread-executor p.exec/thread-executor))

;;--------------------------------------------------------------------------------------
;; integer serdes used for GRPC framing
;;--------------------------------------------------------------------------------------
(defn- bytes->num
  "Deserializes an integer from a byte-array.

  Shamelessly borrowed from https://gist.github.com/pingles/1235344"
  [data]
  (->> data
       (map-indexed
        (fn [i x]
          (bit-shift-left (bit-and x 0x0FF)
                          (* 8 (- (count data) i 1)))))
       (reduce bit-or)))

(defn- num->bytes
  "Serializes an integer to a byte-array."
  ^bytes [num]
  (byte-array (for [i (range 4)]
                (-> (unsigned-bit-shift-right num
                                              (* 8 (- 4 i 1)))
                    (bit-and 0x0FF)))))

;;======================================================================================
;; GRPC length-prefixed-message (LPM) codec
;;======================================================================================
;;
;; GRPC encodes protobuf messages as:
;;
;; [
;;  1b : compressed? (0 = no, 1 = yes)
;;  4b : length of message
;;  Nb : N = length bytes of optionally compressed protobuf
;;  <encoded payload>
;; ]
;;
;; Reference:
;    https://github.com/grpc/grpc/blob/master/doc/PROTOCOL-HTTP2.md#requests
;;======================================================================================

;;--------------------------------------------------------------------------------------
;; Decoder
;;--------------------------------------------------------------------------------------
(defn- decoder-stream
  ^InputStream [is compressed? decompressor]
  (if (and compressed? (some? decompressor))
    (decompressor is)
    is))

(defn- decode-header
  "Decodes 5-bytes into a {compressed? len} tuple"
  [hdr]
  (let [compressed? (first hdr)
        lenbuf (rest hdr)]
    {:compressed? (pos? compressed?) :len (bytes->num lenbuf)}))

(defn- blocking-read
  [^InputStream is b off len]
  (let [alen (.read is b off len)]
    (cond
      (= alen -1) (throw (ex-info "short-read" {}))
      (< alen len) (recur is b (+ off alen) (- len alen))
      :default true)))

(defn- read-header
  [^InputStream is]
  (let [hdr (byte-array 5)]
    (blocking-read is hdr 0 5)
    (decode-header hdr)))

(defn- decode-body
  "Decodes a LPM payload based on a previously decoded header (see [[decode-header]])"
  [f is {:keys [compressed? len] :as header} options]
  (-> (BoundedInputStream. is len)
      (decoder-stream compressed? options)
      f))

(defn- decode->seq [f ^InputStream is decompressor]
  (lazy-seq (when (pos? (.available is))
              (let [hdr (read-header is)]
                (cons (decode-body f is hdr decompressor) (decode->seq f is decompressor))))))

;;--------------------------------------------------------------------------------------------

(defn decode
  "
Takes a parsing function, a pair of input/output [core.async channels](https://clojuredocs.org/clojure.core.async/chan), and an optional codec and
decodes a stream of [length-prefixed-message](https://github.com/grpc/grpc/blob/master/doc/PROTOCOL-HTTP2.md#requests) (LPM).

#### Parameters

| Value           | Type                 | Description                                                                  |
|-----------------|----------------------|---------------------------------------------------------------------------|
| **f**           | _(fn [is])_          | A protobuf decoder function with an arity of 1 that accepts an instance of [InputStream](https://docs.oracle.com/javase/7/docs/api/java/io/InputStream.html), such as the pb-> functions produced by the protoc-gen-clojure compiler |
| **input**       | _core.async/channel_ | A core.async channel that carries LPM encoded bytes, one byte per [(take!)](https://clojuredocs.org/clojure.core.async/take!).  Closing the input will shut down the pipeline. |
| **output**      | _core.async/channel_ | A core.async channel that carries decoded protobuf records.  Will be closed when the pipeline shuts down. |
| **options**     | _map_                | See _Options_

##### Options

| Value              | Type             | Default      | Description                                                            |
|--------------------|-----------------------------------------------------------------|
| **content-coding** | _string_         | \"identity\" | See _Content-coding_ table    |
| **codecs**         | _map_            | [[protojure.grpc.codec.compression/builtin-codecs]] | The dictionary of codecs to utilize |
| **tmo**            | _unsigned int_   | 5000ms       | A timeout, in ms, for receiving the remaining LPM payload bytes once the header has been received. |

###### Example

```
{:content-coding \"gzip\"
 :codecs mycodecs
 :tmo 1000}
```

##### Content-coding
The value for the **content-coding** option must be one of

| Value          | Comment                                   |
|----------------|-------------------------------------------|
| nil            | no compression                            |
| \"identity\"   | no compression                            |
| _other string_ | retrieves the codec from _codecs_ option  |


"
  [f input output {:keys [codecs content-coding tmo] :or {codecs compression/builtin-codecs tmo 5000} :as options}]
  (let [decompressor (when (and (some? content-coding) (not= content-coding "identity"))
                       (compression/decompressor codecs content-coding))]
    (p/create
     (fn [resolve reject]
       (try
         (loop []
           (if-let [buf (<!! input)]
             (let [is (pio/new-inputstream {:ch input :tmo tmo :buf buf})]
               (doseq [msg (decode->seq f is decompressor)]
                 (when (some? msg)
                   (log/trace "Decoded: " msg)
                   (>!! output msg)))
               (recur))
             (resolve :ok)))
         (catch Exception e
           (reject e))
         (finally
           (log/trace "closing output channel")
           (async/close! output))))
     lpm-thread-executor)))

;;--------------------------------------------------------------------------------------
;; Encoder
;;--------------------------------------------------------------------------------------
(defn- encode-buffer [buf len compressed? ^OutputStream os]
  (.write os (int (if compressed? 1 0)))
  (.write os (num->bytes len) 0 4)
  (when (pos? len)
    (.write os buf 0 len)))

(defn- encode-uncompressed [msg os]
  (let [buf (->pb msg)
        len (count buf)]
    (encode-buffer buf len false os)))

(defn- compress-buffer [compressor ^bytes buf]
  (let [os (ByteArrayOutputStream.)]
    (with-open [cos (compressor os)]
      (io/copy buf ^OutputStream cos))
    (.toByteArray os)))

(defn- encode-maybe-compressed
  "This function will encode the message either with or without compression,
  depending on whichever results in the smaller message"
  [msg compressor os]
  (let [buf (->pb msg)
        len (count buf)
        cbuf (compress-buffer compressor buf)
        clen (count buf)]
    (if (< clen len)
      (encode-buffer cbuf clen true os)
      (encode-buffer buf len false os))))

;;--------------------------------------------------------------------------------------------
(defn encode
  "
Takes an input and output [core.async channel](https://clojuredocs.org/clojure.core.async/chan), along with an optional codec and
encodes a stream of 0 or more [length-prefixed-message](https://github.com/grpc/grpc/blob/master/doc/PROTOCOL-HTTP2.md#requests)
messages.

#### Parameters

| Value           | Type                 | Description                                                               |
|-----------------|----------------------|---------------------------------------------------------------------------|
| **f**           | _(fn [init])_        | A protobuf encoder function with an arity of 1 that accepts an init-map, such as the new-XX functions produced by the protoc-gen-clojure compiler |
| **input**       | _core.async/channel_ | A core.async channel expected to carry maps that will be transformed to protobuf messages based on _f_                                            |
| **output**      | _core.async/channel_ | A core.async channel that will carry bytes representing the encoded messages. See _max-frame-size_.  Will be closed when the pipeline shuts down. |
| **options**     | _map_                | See _Options_                                                             |

##### Options

| Value              | Type     | Default      | Description                                                                         |
|--------------------|---------------------------------------------------------------------------------------------------------------|
| **content-coding** | _String_ | \"identity\" | See the _content-coding_ section of [[decode]]                                      |
| **codecs**         | _map_    | [[protojure.grpc.codec.compression/builtin-codecs]] | The dictionary of codecs to utilize          |
| **max-frame-size** | _UInt32_ | 0            | Maximum frame-size emitted on _output_ channel.  See _Output channel details_ below |

##### Output channel details

The _max-frame-size_ option dictates how bytes are encoded on the _output_ channel:

  - **0**: (Default) Indicates 'stream' mode: Bytes are encoded one byte per [(put!)](https://clojuredocs.org/clojure.core.async/put!).
  - **>0**: Indicates 'framed' mode: The specified value will dictate the upper bound on byte-array based frames emitted to the output channel.

###### Example

```
{:content-coding \"gzip\"
 :codecs mycodecs
 :max-frame-size 16384}
```
  "
  [f input output {:keys [codecs content-coding max-frame-size] :or {codecs compression/builtin-codecs} :as options}]
  (let [os (pio/new-outputstream (cond-> {:ch output} (some? max-frame-size) (assoc :max-frame-size max-frame-size)))
        compressor (when (and (some? content-coding) (not= content-coding "identity"))
                     (compression/compressor codecs content-coding))]
    (p/create
     (fn [resolve reject]
       (try
         (loop []
           (if-let [_msg (<!! input)]
             (do
               (log/trace "Encoding: " _msg)
               (let [msg (f _msg)]
                 (if (some? compressor)
                   (encode-maybe-compressed msg compressor os)
                   (encode-uncompressed msg os))
                 (.flush os)
                 (recur)))
             (resolve :ok)))
         (catch Exception e
           (reject e))
         (finally
           (.close os))))
     lpm-thread-executor)))
