;; Copyright © 2019 State Street Bank and Trust Company.  All rights reserved
;; Copyright © 2019 Manetu, Inc.  All rights reserved
;;
;; SPDX-License-Identifier: Apache-2.0

(ns protojure.protobuf.serdes.complex
  "Serializer/deserializer support for complex protobuf types."
  (:require [protojure.protobuf.serdes.core :refer :all]
            [protojure.protobuf.serdes.stream :as stream]))

(set! *warn-on-reflection* true)

(defn cis->map
  "Deserialize a wire format map-type to user format [key val]"
  [f is]
  (let [{:keys [key value]} (f is)]
    (partial into {key value})))

(defn cis->repeated
  "Deserialize an 'unpacked' repeated type (see [[cis->packablerepeated]])"
  [f is]
  (fn [coll]
    (conj (or coll []) (f is))))

(defn- repeated-seq
  "Returns a lazy sequence of repeated items on an input-stream"
  [f is]
  (lazy-seq (when (not (stream/end? is))
              (cons (f is) (repeated-seq f is)))))

(defn cis->packedrepeated
  "Deserialize a 'packed' repeated type (see [[cis->packablerepeated]])"
  [f is]
  (fn [coll]
    (cis->embedded #(reduce conj (or coll []) (repeated-seq f %)) is)))

(defn cis->packablerepeated
  "
  Deserialize a repeated type which may optionally support [packed format](https://developers.google.com/protocol-buffers/docs/encoding#packed).
  The field type will indicate unpacked (0) vs packed (2).
  "
  [tag f is]
  (let [type (bit-and 0x2 tag)]
    (case type
      0 (cis->repeated f is)
      2 (cis->packedrepeated f is)
      (cis->undefined tag is))))

;; FIXME: Add support for optimizing packable types
(defn write-repeated
  "Serialize a repeated type"
  [f tag items os]
  (doseq [item items]
    (f tag item os)))

(defn write-map
  "Serialize user format [key val] using given map item constructor"
  [constructor tag items os]
  (write-repeated write-embedded tag (map (fn [[key value]] (constructor {:key key :value value})) items) os))

