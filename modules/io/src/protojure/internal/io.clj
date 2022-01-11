;; Copyright © 2019 State Street Bank and Trust Company.  All rights reserved
;; Copyright © 2019-2022 Manetu, Inc.  All rights reserved
;;
;; SPDX-License-Identifier: Apache-2.0

(ns ^:no-doc protojure.internal.io
  (:require [clojure.core.async :refer [<! >! <!! alt!! go go-loop] :as async])
  (:import (java.nio ByteBuffer)
           (protojure.internal.io ProxyInputStream AsyncInputStream
                                  ProxyOutputStream AsyncOutputStream)))

(set! *warn-on-reflection* true)

;;--------------------------------------------------------------------------------------------
;; InputStream
;;--------------------------------------------------------------------------------------------

(defn- is-rx
  ^ByteBuffer [data-ch tmo]
  (if (some? tmo)
    (let [tmo-ch (async/timeout tmo)]
      (alt!!
        data-ch ([b] b)
        tmo-ch ([_] (throw (ex-info "Timeout" {:context "Timeout waiting for LPM bytes"})))))
    (<!! data-ch)))

(defn- is-get-buffer
  ^ByteBuffer [{:keys [ch tmo buf] :as ctx}]
  (let [^ByteBuffer _buf @buf]
    (if (or (nil? _buf) (false? (.hasRemaining _buf)))
      (when-let [_buf (is-rx ch tmo)]
        (reset! buf _buf)
        _buf)
      _buf)))

(defn- is-available
  [{:keys [buf] :as ctx}]
  (let [^ByteBuffer _buf @buf]
    (if (and (some? _buf) (.hasRemaining _buf))
      (.remaining _buf)
      0)))

(defn- is-read
  [ctx b off len]
  (let [buf (is-get-buffer ctx)]
    (if (some? buf)
      (let [len (min len (.remaining buf))]
        (when (pos? len)
          (.get buf b off len))
        len)
      -1)))

(defn- is-read-int
  [ctx]
  (let [buf (is-get-buffer ctx)]
    (or (some->> buf (.get) (bit-and 0xff) int)
        -1)))

(defn new-inputstream
  ^java.io.InputStream [{:keys [ch tmo buf]}]
  (let [ctx {:ch ch :tmo tmo :buf (atom buf)}]
    (ProxyInputStream.
     (reify AsyncInputStream
       (available [_]
         (is-available ctx))
       (read_int [_]
         (is-read-int ctx))
       (read_bytes [_ b]
         (is-read ctx b 0 (count b)))
       (read_offset [_ b off len]
         (is-read ctx b off len))))))

;;--------------------------------------------------------------------------------------------
;; OutputStream
;;--------------------------------------------------------------------------------------------
(defn- os-flush
  [{:keys [ch frame-size buf]}]
  (let [^ByteBuffer _buf @buf]
    (when (pos? (.position _buf))
      (async/>!! ch (.flip _buf))
      (reset! buf (ByteBuffer/allocate frame-size)))))

(defn- os-maybe-flush
  [{:keys [buf] :as ctx}]
  (let [^ByteBuffer _buf @buf]
    (when (zero? (.remaining _buf))
      (os-flush ctx))))

(defn- os-close
  [{:keys [ch] :as ctx}]
  (os-flush ctx)
  (async/close! ch))

(defn- os-write
  [{:keys [buf] :as ctx} b off len]
  (os-maybe-flush ctx)
  (let [^ByteBuffer _buf @buf
        alen (min len (.remaining _buf))]
    (when (pos? alen)
      (.put _buf b off alen)
      (when (< alen len)
        (recur ctx b (+ off alen) (- len alen))))))

(defn new-outputstream
  ^java.io.OutputStream [{:keys [ch max-frame-size] :or {max-frame-size 16384} :as options}]
  {:pre [(and (some? max-frame-size) (pos? max-frame-size))]}
  (let [ctx {:ch ch :frame-size max-frame-size :buf (atom (ByteBuffer/allocate max-frame-size))}]
    (ProxyOutputStream.
     (reify AsyncOutputStream
       (flush [_]
         (os-flush ctx))
       (close [_]
         (os-close ctx))
       (write_int [_ b]
         (let [b (bit-and 0xff b)]
           (os-write ctx (byte-array [b]) 0 1)))
       (write_bytes [_ b]
         (os-write ctx b 0 (count b)))
       (write_offset [_ b off len]
         (os-write ctx b off len))))))
