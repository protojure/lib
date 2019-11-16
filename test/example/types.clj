;; Copyright © 2019 State Street Bank and Trust Company.  All rights reserved
;;
;; SPDX-License-Identifier: Apache-2.0

(ns example.types
  (:require [protojure.protobuf :as pb]
            [protojure.protobuf.serdes :refer :all])
  (:import (com.google.protobuf CodedInputStream)))

;-----------------------------------------------------------------------------
; Money
;
; implementation of https://github.com/googleapis/googleapis/blob/master/google/type/money.proto
;-----------------------------------------------------------------------------
(defrecord Money [currency_code units nanos]
  pb/Writer

  (serialize [this os]
    (write-String 1 (:currency_code this) os)
    (write-Int64 2 (:units this) os)
    (write-Int32 3 (:nanos this) os)))

(def Money-defaults {:currency_code "" :units 0 :nanos 0})

(defn cis->Money
  "CodedInputStream to Money"
  [is]
  (->> (tag-map
        (fn [tag index]
          (case index
            1 [:currency_code (cis->String is)]
            2 [:units (cis->Int64 is)]
            3 [:nanos (cis->Int32 is)]
            [index (cis->undefined tag is)]))
        is)
       (merge Money-defaults)
       (map->Money)))

(defn ecis->Money
  "Embedded CodedInputStream to Money"
  [is]
  (cis->embedded cis->Money is))

(defn new-Money
  "Creates a new instance from a map, similar to map->Money except that
  it properly accounts for nested messages, when applicable.
  "
  [init]
  (-> (merge Money-defaults init)
      (map->Money)))

(defn pb->Money
  "Protobuf to Money"
  [input]
  (-> input
      CodedInputStream/newInstance
      cis->Money))

;-----------------------------------------------------------------------------
; SimpleRepeated
;-----------------------------------------------------------------------------
(defrecord SimpleRepeated [data]
  pb/Writer

  (serialize [this os]
    (write-repeated write-Int32 1 (:data this) os)))

(def SimpleRepeated-defaults {:data []})

(defn cis->SimpleRepeated
  "CodedInputStream to SimpleRepeated"
  [is]
  (->> (tag-map
        (fn [tag index]
          (case index
            1 [:data (cis->packablerepeated tag cis->Int32 is)]

            [index (cis->undefined tag is)]))
        is)
       (merge SimpleRepeated-defaults)
       (map->SimpleRepeated)))

(defn ecis->SimpleRepeated
  "Embedded CodedInputStream to SimpleRepeated"
  [is]
  (cis->embedded cis->SimpleRepeated is))

(defn new-SimpleRepeated
  "Creates a new instance from a map, similar to map->SimpleRepeated except that
  it properly accounts for nested messages, when applicable.
  "
  [init]
  (-> (merge SimpleRepeated-defaults init)
      (map->SimpleRepeated)))

(defn pb->SimpleRepeated
  "Protobuf to SimpleRepeated"
  [input]
  (-> input
      CodedInputStream/newInstance
      cis->SimpleRepeated))

;-----------------------------------------------------------------------------
; SimpleString
;-----------------------------------------------------------------------------
(defrecord SimpleString [s]
  pb/Writer

  (serialize [this os]
    (write-String 1  {:optimize true} (:s this) os)))

(def SimpleString-defaults {:s ""})

(defn cis->SimpleString
  "CodedInputStream to SimpleString"
  [is]
  (->> (tag-map
        (fn [tag index]
          (case index
            1 [:s (cis->String is)]

            [index (cis->undefined tag is)]))
        is)
       (merge SimpleString-defaults)
       (map->SimpleString)))

(defn ecis->SimpleString
  "Embedded CodedInputStream to SimpleString"
  [is]
  (cis->embedded cis->SimpleString is))

(defn new-SimpleString
  "Creates a new instance from a map, similar to map->SimpleString except that
  it properly accounts for nested messages, when applicable.
  "
  [init]
  (-> (merge SimpleString-defaults init)
      (map->SimpleString)))

(defn pb->SimpleString
  "Protobuf to SimpleString"
  [input]
  (-> input
      CodedInputStream/newInstance
      cis->SimpleString))

;-----------------------------------------------------------------------------
; AllThingsMap-MSimpleEntry
;-----------------------------------------------------------------------------
(defrecord AllThingsMap-MSimpleEntry [key value]
  pb/Writer

  (serialize [this os]
    (write-String 1  {:optimize true} (:key this) os)
    (write-Int32 2  {:optimize true} (:value this) os)))

(def AllThingsMap-MSimpleEntry-defaults {:key "" :value 0})

(defn cis->AllThingsMap-MSimpleEntry
  "CodedInputStream to AllThingsMap-MSimpleEntry"
  [is]
  (->> (tag-map
        (fn [tag index]
          (case index
            1 [:key (cis->String is)]
            2 [:value (cis->Int32 is)]

            [index (cis->undefined tag is)]))
        is)
       (merge AllThingsMap-MSimpleEntry-defaults)
       (map->AllThingsMap-MSimpleEntry)))

(defn ecis->AllThingsMap-MSimpleEntry
  "Embedded CodedInputStream to AllThingsMap-MSimpleEntry"
  [is]
  (cis->embedded cis->AllThingsMap-MSimpleEntry is))

(defn new-AllThingsMap-MSimpleEntry
  "Creates a new instance from a map, similar to map->AllThingsMap-MSimpleEntry except that
  it properly accounts for nested messages, when applicable.
  "
  [init]
  (-> (merge AllThingsMap-MSimpleEntry-defaults init)
      (map->AllThingsMap-MSimpleEntry)))

(defn pb->AllThingsMap-MSimpleEntry
  "Protobuf to AllThingsMap-MSimpleEntry"
  [input]
  (-> input
      CodedInputStream/newInstance
      cis->AllThingsMap-MSimpleEntry))

;-----------------------------------------------------------------------------
; AllThingsMap-MComplexEntry
;-----------------------------------------------------------------------------
(defrecord AllThingsMap-MComplexEntry [key value]
  pb/Writer

  (serialize [this os]
    (write-String 1  {:optimize true} (:key this) os)
    (write-embedded 2 (:value this) os)))

(def AllThingsMap-MComplexEntry-defaults {:key ""})

(defn cis->AllThingsMap-MComplexEntry
  "CodedInputStream to AllThingsMap-MComplexEntry"
  [is]
  (->> (tag-map
        (fn [tag index]
          (case index
            1 [:key (cis->String is)]
            2 [:value (ecis->SimpleString is)]

            [index (cis->undefined tag is)]))
        is)
       (merge AllThingsMap-MComplexEntry-defaults)
       (map->AllThingsMap-MComplexEntry)))

(defn ecis->AllThingsMap-MComplexEntry
  "Embedded CodedInputStream to AllThingsMap-MComplexEntry"
  [is]
  (cis->embedded cis->AllThingsMap-MComplexEntry is))

(defn new-AllThingsMap-MComplexEntry
  "Creates a new instance from a map, similar to map->AllThingsMap-MComplexEntry except that
  it properly accounts for nested messages, when applicable.
  "
  [init]
  (-> (merge AllThingsMap-MComplexEntry-defaults init)
      (cond-> (contains? init :value) (update :value new-SimpleString))
      (map->AllThingsMap-MComplexEntry)))

(defn pb->AllThingsMap-MComplexEntry
  "Protobuf to AllThingsMap-MComplexEntry"
  [input]
  (-> input
      CodedInputStream/newInstance
      cis->AllThingsMap-MComplexEntry))

;-----------------------------------------------------------------------------
; AllThingsMap
;-----------------------------------------------------------------------------
(defrecord AllThingsMap [s i mSimple mComplex sSimple oe]
  pb/Writer

  (serialize [this os]
    (write-String 1  {:optimize true} (:s this) os)
    (write-Int32 2  {:optimize true} (:i this) os)
    (write-map new-AllThingsMap-MSimpleEntry 3 (:mSimple this) os)
    (write-map new-AllThingsMap-MComplexEntry 4 (:mComplex this) os)
    (write-embedded 5 (:sSimple this) os)))

(def AllThingsMap-defaults {:s "" :i 0 :mSimple [] :mComplex []})

(defn cis->AllThingsMap
  "CodedInputStream to AllThingsMap"
  [is]
  (->> (tag-map
        (fn [tag index]
          (case index
            1 [:s (cis->String is)]
            2 [:i (cis->Int32 is)]
            3 [:mSimple (cis->map ecis->AllThingsMap-MSimpleEntry is)]
            4 [:mComplex (cis->map ecis->AllThingsMap-MComplexEntry is)]
            5 [:sSimple (ecis->SimpleString is)]

            [index (cis->undefined tag is)]))
        is)
       (merge AllThingsMap-defaults)
       (map->AllThingsMap)))

(defn ecis->AllThingsMap
  "Embedded CodedInputStream to AllThingsMap"
  [is]
  (cis->embedded cis->AllThingsMap is))

(defn new-AllThingsMap
  "Creates a new instance from a map, similar to map->AllThingsMap except that
  it properly accounts for nested messages, when applicable.
  "
  [init]
  (-> (merge AllThingsMap-defaults init)
      (cond-> (contains? init :sSimple) (update :sSimple new-SimpleString))
      (map->AllThingsMap)))

(defn pb->AllThingsMap
  "Protobuf to AllThingsMap"
  [input]
  (-> input
      CodedInputStream/newInstance
      cis->AllThingsMap))

