;; Copyright © 2019-2022 Manetu, Inc.  All rights reserved
;;
;; SPDX-License-Identifier: Apache-2.0
(ns protojure.protobuf.any
  "Support for the 'Any' type: https://developers.google.com/protocol-buffers/docs/proto3#any"
  (:require [protojure.protobuf.protocol :as protocol]
            [protojure.protobuf :as serdes]
            [com.google.protobuf :as google]))

(def default-path "type.googleapis.com/")

(defn- fqtype [type]
  (str default-path type))

(defn- load-registry
  "Finds all instances of Protojure records via metadata and creates a type-url registry for any->"
  []
  (->> (all-ns)
       (map ns-name)
       (map ns-interns)
       (map vals)
       (reduce concat)
       (filter (fn [x] (contains? (meta x) ::record)))
       (map var-get)
       (reduce (fn [acc {:keys [type decoder]}] (assoc acc (fqtype type) decoder)) {})))

(def registry (memoize load-registry))

(defn- find-type [url]
  (get (registry) url))

(defn ->any
  "Encodes a Protojure record that implements the requisite type-reflection protocol to an Any record"
  [msg]
  (google/new-Any {:type-url (fqtype (protocol/gettype msg))
                   :value (serdes/->pb msg)}))

(defn any->
  "Decodes an Any record to its native type if the type is known to our system"
  [{:keys [type-url value]}]
  (if (some? type-url)
    (if-let [f (find-type type-url)]
      (f value)
      (throw (ex-info "Any::type-url not found" {:type-url type-url})))
    (throw (ex-info "Any record missing :type-url" {}))))

(defn pb->
  "Decodes a raw protobuf message/stream encoded as an Any to its native type, if the type is known to our system"
  [pb]
  (any-> (google/pb->Any pb)))
