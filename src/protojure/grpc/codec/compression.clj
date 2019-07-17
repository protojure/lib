;; Copyright © 2019 State Street Bank and Trust Company.  All rights reserved
;;
;; SPDX-License-Identifier: Apache-2.0

(ns protojure.grpc.codec.compression
  (:import (org.apache.commons.compress.compressors.gzip GzipCompressorInputStream
                                                         GzipCompressorOutputStream
                                                         GzipParameters)
           (org.apache.commons.compress.compressors.snappy FramedSnappyCompressorInputStream
                                                           FramedSnappyCompressorOutputStream)
           (org.apache.commons.compress.compressors.deflate DeflateCompressorInputStream
                                                            DeflateCompressorOutputStream)))

;;--------------------------------------------------------------------------------------
;; compression support
;;--------------------------------------------------------------------------------------
(def ^:no-doc _builtin-codecs
  [{:name   "gzip"
    :input  #(GzipCompressorInputStream. %)
    :output #(let [params (GzipParameters.)] (.setCompressionLevel params 9) (GzipCompressorOutputStream. % params))}

   {:name   "snappy"
    :input  #(FramedSnappyCompressorInputStream. %)
    :output #(FramedSnappyCompressorOutputStream. %)}

   {:name   "deflate"
    :input  #(DeflateCompressorInputStream. %)
    :output #(DeflateCompressorOutputStream. %)}])

(def builtin-codecs
  "
A map of built-in compression [codecs](https://en.wikipedia.org/wiki/Codec), keyed by name.

| Name         | Description                                            |
|--------------|--------------------------------------------------------|
| \"gzip\"     | [gzip](https://en.wikipedia.org/wiki/Gzip) codec       |
| \"deflate\"  | [deflate](https://en.wikipedia.org/wiki/DEFLATE) codec |
| \"snappy\"   | [snappy](https://github.com/google/snappy) codec       |

These built-in codecs are used by default, unless the caller overrides the codec dictionary.  A common use
case would be to augment the built-in codecs with 1 or more custom codecs.

#### Custom codecs

##### Map specification
The codec map consists of a collection of name/value pairs of codec-specifications keyed by a string representing
the name of the codec.

```
[\"mycodec\" {:input inputfn :output outputfn}]
```

where

- **inputfn**: a (fn) that accepts an InputStream input, and returns an InputStream
- **outputfn**: a (fn) that accepts an OutputStream input, and returns an OutputStream

##### Example

```
(assoc builtin-codecs
       \"mycodec\" {:input clojure.core/identity :output clojure.core/identity})
```

**N.B.**: The output stream returned in _outputfn_ will have its (.close) method invoked to finalize
compression.  Therefore, the use of `identity` above would be problematic in the real-world since we
may not wish to actually close the underlying stream at that time.  Therefore, its use above is only for
simplistic demonstration.  A functional \"pass through\" example could be built using something like
[CloseShieldOutputStream](https://commons.apache.org/proper/commons-io/javadocs/api-2.4/org/apache/commons/io/output/CloseShieldOutputStream.html)
  "
  (->> _builtin-codecs (map #(vector (:name %) %)) (into {})))

(defn- get-codec-by-polarity [factory polarity]
  (if-let [codec (get factory polarity)]
    codec
    (throw (ex-info "CODEC polarity not found" {:codec factory :polarity polarity}))))

(defn- get-codec [codecs type polarity]
  (if-let [factory (get codecs type)]
    (get-codec-by-polarity factory polarity)
    (throw (ex-info "Unknown CODEC name" {:name type :polarity polarity}))))

(defn ^:no-doc compressor [codecs type] (get-codec codecs type :output))
(defn ^:no-doc decompressor [codecs type] (get-codec codecs type :input))

