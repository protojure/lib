;; Copyright © 2019 State Street Bank and Trust Company.  All rights reserved
;;
;; SPDX-License-Identifier: Apache-2.0

(ns protojure.protobuf.serdes
  "Serializer/deserializer support for fundamental protobuf types."
  (:require [protojure.protobuf :as pb])
  (:import (com.google.protobuf CodedInputStream
                                CodedOutputStream
                                WireFormat
                                UnknownFieldSet
                                ExtensionRegistry
                                ByteString)))

(defn tag-map
  "
  Returns a lazy sequence consisting of the result of applying f to the set of
  protobuf objects delimited by protobuf tags.

  #### Parameters

  | Value    | Type               | Description                                                                                    |
  |----------|--------------------|------------------------------------------------------------------------------------------------|
  | **init** | _map_              | A map of initial values                                                                        |
  | **f**    | _(fn [tag index])_ | An arity-2 function that accepts a tag and index and returns a [k v] (see _Return type_ below) |
  | **is**   | [CodedInputStream](https://developers.google.com/protocol-buffers/docs/reference/java/com/google/protobuf/CodedInputStream) | An input stream containing serialized protobuf data |

  #### Return Type

  _f_ should evaluate to a 2-entry vector in the form [key value], where:

  - _key_ is either
       - a keyword representing the field name when the index is known
       - simply the index value when it is not
  - _value_ is either
       - a value that will be returned verbatim to be associated to the _key_
       - a function that will take a collection of previously deserialized values with the same tag and update it to incorporate the new value (to support _repeated_ types, etc)


  #### Example

  ```
  (tag-map
      (fn [tag index]
           (case index
              1 [:currency_code (cis->String is)]
              2 [:units (cis->Int64 is)]
              3 [:nanos (cis->Int32 is)]
              [index (cis->undefined tag is)]))
      is))
  ```
  "
  ([f is]
   (tag-map {} f is))
  ([init f is]
   (loop [acc init tag (.readTag is)]
     (if (pos? tag)
       (let [[k v] (f tag (bit-shift-right tag 3))]
         (recur (if (fn? v)
                  (update acc k v)
                  (assoc acc k v))
                (.readTag is)))
       acc))))

(defmacro defparsefn [type]
  (let [name (symbol (str "cis->" type))
        sym (symbol (str "read" type))
        doc (format "Deserialize a '%s' type" type)]
    `(defn ~name ~doc [is#]
       (. is# ~sym))))

(defmacro defwritefn [type default?]
  (let [name (symbol (str "write-" type))
        sym (symbol (str "write" type))
        doc (format "Serialize a '%s' type" type)]
    `(defn ~name ~doc
       ([tag# value# os#]
        (~name tag# {} value# os#))
       ([tag# options# value# os#]
        (when-not (and (get options# :optimize true) (~default? value#))
          (. os# ~sym tag# value#))))))

(defmacro defserdes [type default?]
  `(do
     (defparsefn ~type)
     (defwritefn ~type ~default?)))

(def default-scalar? #(or (nil? %) (zero? %)))
(def default-string? empty?)
(def default-bool? #(not (true? %)))

(defmacro defscalar [type]
  `(defserdes ~type default-scalar?))

(defscalar "Double")
(defscalar "Enum")
(defscalar "Fixed32")
(defscalar "Fixed64")
(defscalar "Float")
(defscalar "Int32")
(defscalar "Int64")
(defscalar "SFixed32")
(defscalar "SFixed64")
(defscalar "SInt32")
(defscalar "SInt64")
(defscalar "UInt32")
(defscalar "UInt64")

(defserdes "String" default-string?)
(defserdes "Bool" default-bool?)

;; manually implement the "Bytes" scalar so we can properly handle native byte-array import/export
(defn cis->Bytes
  "Deserialize 'Bytes' type"
  [is]
  (.toByteArray (.readBytes is)))

(defn write-Bytes
  "Serialize 'Bytes' type"
  ([tag value os]
   (write-Bytes tag {} value os))
  ([tag {:keys [optimize] :or {optimize true} :as options} value os]
   (when-not (and optimize (empty? value))
     (let [bytestring (ByteString/copyFrom value)]
       (.writeBytes os tag bytestring)))))

(defn cis->undefined
  "Deserialize an unknown type, retaining its tag/type"
  [tag is]
  (let [num (WireFormat/getTagFieldNumber tag)
        type (WireFormat/getTagWireType tag)]
    (case type
      0 (.readInt64 is)
      1 (.readFixed64 is)
      2 (.readBytes is)
      3 (.readGroup is num (UnknownFieldSet/newBuilder) (ExtensionRegistry/getEmptyRegistry))
      4 nil
      5 (.readFixed32 is))))

(defn cis->embedded
  "Deserialize an embedded type, where **f** is an (fn) that can deserialize the embedded message"
  [f is]
  (let [len (.readRawVarint32 ^CodedInputStream is)
        lim (.pushLimit is len)]
    (let [result (f is)]
      (.popLimit is lim)
      result)))

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
  (lazy-seq (when (not (.isAtEnd is))
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
  (let [type (WireFormat/getTagWireType tag)]
    (case type
      0 (cis->repeated f is)
      2 (cis->packedrepeated f is)
      (cis->undefined tag is))))

(defn write-embedded
  "Serialize an embedded type along with tag/length metadata"
  [tag item os]
  (when (some? item)
    (let [bytes (pb/->pb item)
          len (count bytes)]
      (when-not (zero? len)
        (.writeTag os tag 2);; embedded messages are always type=2 (string)
        (.writeUInt32NoTag os len)
        (.writeRawBytes os bytes)))))

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