;; Copyright © 2019 Manetu, Inc.  All rights reserved
;;
;; SPDX-License-Identifier: Apache-2.0

(ns protojure.protobuf.serdes.stream
  (:import (com.google.protobuf CodedInputStream)))

(defn end? [is]
  (.isAtEnd ^CodedInputStream is))

(defn read-tag [is]
  (.readTag ^CodedInputStream is))

(defn new-cis [src]
  (CodedInputStream/newInstance src))
