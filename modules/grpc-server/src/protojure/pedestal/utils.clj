;; Copyright © Manetu, Inc.  All rights reserved
;;
;; SPDX-License-Identifier: Apache-2.0
(ns protojure.pedestal.utils)

(defn get-header [headers re]
  (some (fn [[k v]] (when (some? (re-matches re k)) v)) headers))