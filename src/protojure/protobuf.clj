;; Copyright © 2019 State Street Bank and Trust Company.  All rights reserved
;;
;; SPDX-License-Identifier: Apache-2.0

(ns protojure.protobuf
  "Main API entry point for protobuf applications"
  (:require [protojure.protobuf.protocol :as p])
  (:import (com.google.protobuf CodedOutputStream)
           (java.io OutputStream ByteArrayOutputStream)
           (java.nio ByteBuffer)))

(set! *warn-on-reflection* true)

(defn- serialize! [msg ^CodedOutputStream os]
  (p/serialize msg os)
  (.flush os))

(defmulti #^{:private true} serialize (fn [msg output] (type output)))
(defmethod serialize OutputStream
  [msg ^OutputStream output]
  (serialize! msg (CodedOutputStream/newInstance output)))
(defmethod serialize ByteBuffer
  [msg ^ByteBuffer output]
  (serialize! msg (CodedOutputStream/newInstance output)))
(defmethod serialize ByteBuffer
  [msg ^ByteBuffer output]
  (serialize! msg (CodedOutputStream/newInstance output)))

(defn ->pb
  "Serialize a record implementing the [[Writer]] protocol into protobuf bytes."
  ([msg]
   (let [os (ByteArrayOutputStream.)]
     (->pb msg os)
     (.toByteArray os)))
  ([msg output]
   (serialize msg output)))
