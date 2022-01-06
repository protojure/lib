;; Copyright © 2019-2022 Manetu, Inc.  All rights reserved
;;
;; SPDX-License-Identifier: Apache-2.0

(ns protojure.grpc.status)

(def -codes
  [[0   :ok                   "success"]
   [1   :cancelled            "The operation was cancelled, typically by the caller."]
   [2   :unknown              "Unknown error."]
   [3   :invalid-argument     "The client specified an invalid argument."]
   [4   :deadline-exceeded    "The deadline expired before the operation could complete."]
   [5   :not-found            "Some requested entity (e.g., file or directory) was not found."]
   [6   :already-exists       "The entity already exists."]
   [7   :permission-denied    "The caller does not have permission to execute the specified operation."]
   [8   :resource-exhausted   "Some resource has been exhausted"]
   [9   :failed-precondition  "The system is not in a state required for the operation's execution"]
   [10  :aborted              "The operation was aborted, typically due to a concurrency issue."]
   [11  :out-of-range         "The operation was attempted past the valid range."]
   [12  :unimplemented        "The operation is not implemented."]
   [13  :internal             "Invariants expected by the underlying system have been broken"]
   [14  :unavailable          "The service is currently unavailable."]
   [15  :data-loss            "Unrecoverable data loss or corruption."]
   [16  :unauthenticated      "The request does not have valid authentication credentials."]])

(def codes
  (->> (map (fn [[code type msg]] {:code code :type type :msg msg}) -codes)
       (reduce (fn [acc {:keys [type] :as v}] (assoc acc type v)) {})))

(def default-error (get codes :unknown))

(defn get-desc [type]
  (get codes type default-error))

(defn get-code [type]
  (:code (get-desc type)))

(defn- -error
  [{:keys [type code msg]}]
  (when-not (= type :ok)
    (throw (ex-info "grpc error" {:code code :msg msg :exception-type ::error}))))

(defn error
  ([type]
   (-error (get-desc type)))
  ([type msg]
   (-> (get-desc type)
       (assoc :msg msg)
       (-error))))
