;; Copyright © 2019 State Street Bank and Trust Company.  All rights reserved
;;
;; SPDX-License-Identifier: Apache-2.0

(ns protojure.protobuf.serdes.core
  "Serializer/deserializer support for fundamental protobuf types."
  (:require [protojure.protobuf :refer [->pb]]
            [protojure.protobuf.serdes.utils :as utils])
  (:import (com.google.protobuf CodedInputStream
                                CodedOutputStream
                                WireFormat
                                UnknownFieldSet
                                ExtensionRegistry
                                ByteString)))

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

(defmacro defscalar [type]
  `(defserdes ~type utils/default-scalar?))

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

(defserdes "String" utils/default-bytes?)
(defserdes "Bool" utils/default-bool?)

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

(defn write-embedded
  "Serialize an embedded type along with tag/length metadata"
  [tag item os]
  (when (some? item)
    (let [bytes (->pb item)
          len (count bytes)]
      (when-not (zero? len)
        (.writeTag os tag 2);; embedded messages are always type=2 (string)
        (.writeUInt32NoTag os len)
        (.writeRawBytes os bytes)))))