;; Copyright © 2019 State Street Bank and Trust Company.  All rights reserved
;;
;; SPDX-License-Identifier: Apache-2.0

(ns protojure.protobuf
  "Main API entry point for protobuf applications"
  (:require [protojure.protobuf.protocol :refer [serialize]])
  (:import (com.google.protobuf CodedOutputStream)
           (java.io ByteArrayOutputStream)))

(defn ->pb
  "Serialize a record implementing the [[Writer]] protocol into protobuf bytes."
  ([msg]
   (let [os (ByteArrayOutputStream.)]
     (->pb msg os)
     (.toByteArray os)))
  ([msg output]
   (let [os (CodedOutputStream/newInstance output)]
     (serialize msg os)
     (.flush os))))
