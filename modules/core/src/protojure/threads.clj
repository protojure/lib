;; Copyright © 2026 Manetu, Inc.  All rights reserved
;;
;; SPDX-License-Identifier: Apache-2.0

(ns protojure.threads
  (:require [promesa.exec :as p.exec]))

(defn get-executor []
  (if p.exec/virtual-threads-available? p.exec/default-vthread-executor p.exec/default-thread-executor))
