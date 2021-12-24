;; Copyright © 2021 Manetu Inc.  All rights reserved
;;
;; SPDX-License-Identifier: Apache-2.0
(ns protojure.promesa
  "promesa v3.0.0 compatibility layer
  ===============================

  The current code is depdendent upon the forkjoin executor for chained operations present in v3.0.0.  This ns is
  a thin layer which retains the forkjoin executor behavior, at least for now.  We may port to the caller-thread
  executor at some point, at which time we can get rid of this layer"
  (:require [promesa.core :as p]
            [promesa.exec :as exec]))

(def create p/create)
(def catch p/catch)
(def all p/all)
(def resolved p/resolved)
(def rejected p/rejected)
(defn finally [*p* f] (p/finally *p* f exec/default-executor))
(defn then [*p* f] (p/then *p* f exec/default-executor))
