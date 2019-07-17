;; Copyright © 2019 State Street Bank and Trust Company.  All rights reserved
;;
;; SPDX-License-Identifier: Apache-2.0

(ns protojure.protobuf
  "Main API entry point for protobuf applications"
  (:import (com.google.protobuf
            CodedOutputStream)))

(defprotocol Writer
  (serialize [this os])
  (length [this]))

(defn ->pb
  "Serialize a record implementing the [[Writer]] protocol into protobuf bytes."
  ([msg]
   (let [len (length msg)
         data (byte-array len)]
     (->pb msg data)
     data))
  ([msg output]
   (let [os (CodedOutputStream/newInstance output)]
     (serialize msg os)
     (.flush os))))
