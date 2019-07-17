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

(defn- defparsefn [type]
  (let [name (symbol (str "cis->" type))
        sym (str "read" type)
        f (eval `(fn [is#]
                   (. is# ~(symbol sym))))
        m {:doc (format "Deserialize a '%s' type" type)
           :arglists '([is])}]
    (intern *ns* (with-meta name m) f)))

(defn- defwritefn [type default?]
  (let [name (symbol (str "write-" type))
        sym (str "write" type)
        f (eval `(fn [os# tag# value#]
                   (. os# ~(symbol sym) tag# value#)))
        m {:doc (format "Serialize a '%s' type" type)
           :arglists '([tag value os] [tag {:keys [optimize] :or {optimize true} :as options} value os])}]
    (intern *ns* (with-meta name m)
            (fn self
              ([tag value os]
               (self tag {} value os))
              ([tag {:keys [optimize] :or {optimize true} :as options} value os]
               (when-not (and optimize (default? value))
                 (f os tag value)))))))

(defn- defsizefn [type default?]
  (let [name (symbol (str "size-" type))
        sym (str "compute" type "Size")
        f (eval `(fn [tag# value#]
                   (. CodedOutputStream ~(symbol sym) tag# value#)))
        m {:doc (format "Compute length of serialized '%s' type" type)
           :arglists '([tag value] [tag {:keys [optimize] :or {optimize true} :as options} value])}]
    (intern *ns* (with-meta name m)
            (fn self
              ([tag value]
               (self tag {} value))
              ([tag {:keys [optimize] :or {optimize true} :as options} value]
               (if-not (and optimize (default? value))
                 (f tag value)
                 0))))))

(defn- defallfn [type default?]
  (defparsefn type)
  (defwritefn type default?)
  (defsizefn type default?))

(def ^:no-doc numeric-scalars
  ["Double"
   "Enum"
   "Fixed32"
   "Fixed64"
   "Float"
   "Int32"
   "Int64"
   "SFixed32"
   "SFixed64"
   "SInt32"
   "SInt64"
   "UInt32"
   "UInt64"])

(defn- init []
  (doseq [type numeric-scalars]
    (defallfn type #(or (nil? %) (zero? %))))

  (defallfn "String" empty?)
  (defallfn "Bool" #(not (true? %))))

(init)

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

(defn size-Bytes
  "Compute length of serialized 'Bytes' type"
  ([tag value]
   (size-Bytes tag {} value))
  ([tag {:keys [optimize] :or {optimize true} :as options} value]
   (if-not (and optimize (empty? value))
     (let [bytestring (ByteString/copyFrom value)]
       (CodedOutputStream/computeBytesSize tag bytestring))
     0)))

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

(defn cis->packedrepeated
  "Deserialize a 'packed' repeated type (see [[cis->packablerepeated]])"
  [f is]
  (fn [coll]
    (let [len (.readRawVarint32 ^CodedInputStream is)]
      (reduce conj (or coll []) (repeatedly len #(f is))))))

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
  (let [len (if (some? item) (pb/length item) 0)]
    (when-not (zero? len)
      (.writeTag os tag 2);; embedded messages are always type=2 (string)
      (.writeUInt32NoTag os len)
      (pb/serialize item os))))

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

(defn size-embedded
  "Compute length of serialized embedded type, including the metadata header"
  [tag item]
  (let [len (if (some? item) (pb/length item) 0)]
    (if-not (zero? len)
      (+
       (size-UInt32 tag {:optimize false} len)  ;; This accounts for the tag+length preamble
       len)                   ;; And this is the embedded item itself
      0)))

(defn size-repeated
  "Compute length of serialized repeated type"
  [f tag items]
  (if-not (empty? items)
    (reduce + (map (partial f tag) items))
    0))

(defn size-map
  "Compute length of user format [key val] using given map item constructor"
  [constructor tag item]
  (size-repeated size-embedded tag (map (fn [[key value]] (constructor {:key key :value value})) item)))
