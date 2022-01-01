;; Copyright © 2019 Manetu, Inc.  All rights reserved
;;
;; SPDX-License-Identifier: Apache-2.0

(ns protojure.protobuf.serdes.stream
  (:import (com.google.protobuf CodedInputStream)
           (java.io InputStream)
           (java.nio ByteBuffer)))

(defn end? [is]
  (.isAtEnd ^CodedInputStream is))

(defn read-tag [is]
  (.readTag ^CodedInputStream is))

(defmulti new-cis (fn [src] (type src)))
(defmethod new-cis InputStream
  [^InputStream src]
  (CodedInputStream/newInstance src))
(defmethod new-cis ByteBuffer
  [^ByteBuffer src]
  (CodedInputStream/newInstance src))
(defmethod new-cis (Class/forName "[B")
  [^bytes src]
  (CodedInputStream/newInstance src))
