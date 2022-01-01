;; Copyright © 2019 State Street Bank and Trust Company.  All rights reserved
;;
;; SPDX-License-Identifier: Apache-2.0

(ns ^:no-doc protojure.internal.grpc.codec.io
  (:require [clojure.core.async :refer [<! >! <!! alt!! go go-loop] :as async]
            [clojure.tools.logging :as log])
  (:import (java.nio ByteBuffer)))

(set! *warn-on-reflection* true)

(defn- take!-with-tmo
  ^ByteBuffer [data-ch tmo]
  (if (some? tmo)
    (let [tmo-ch (async/timeout tmo)]
      (alt!!
        data-ch ([b] b)
        tmo-ch ([_] (throw (ex-info "Timeout" {:context "Timeout waiting for LPM bytes"})))))
    (<!! data-ch)))

(defn- get-buffer
  ^ByteBuffer [{:keys [id ch tmo buf] :as ctx}]
  (let [^ByteBuffer _buf @buf]
    (if (or (nil? _buf) (false? (.hasRemaining _buf)))
      (when-let [_buf (take!-with-tmo ch tmo)]
        (reset! buf _buf)
        _buf)
      _buf)))

;;--------------------------------------------------------------------------------------------
;; InputStream
;;--------------------------------------------------------------------------------------------
(gen-class
 :name protojure.internal.grpc.io.InputStream
 :extends java.io.InputStream
 :prefix is-
 :state state
 :init init
 :constructors {[Object] []})

(defn- is-init [{:keys [ch tmo buf]}]
  [[] {:ch ch :tmo tmo :buf (atom buf)}])

(defn- is-available
  [^protojure.internal.grpc.io.InputStream this]
  (let [{:keys [buf] :as ctx} (.state this)
        ^ByteBuffer _buf @buf]
    (if (and (some? _buf) (.hasRemaining _buf))
      (.remaining _buf)
      0)))

(defn read-impl
  [this b off len]
  (let [ctx (.state this)
        buf (get-buffer ctx)]
    (if (some? buf)
      (let [len (min len (.remaining buf))]
        (when (pos? len)
          (.get buf b off len))
        (log/debug "read:" len "bytes" (.remaining buf) "remain")
        len)
      -1)))

(defn- is-read
  "Reads the next byte of data from the input stream. The value byte is returned as an int in the range 0 to 255.
  See InputStream for further details."
  ([^protojure.internal.grpc.io.InputStream this bytes offset len]
   (read-impl this bytes offset len))
  ([^protojure.internal.grpc.io.InputStream this bytes]
   (read-impl this bytes 0 (count bytes)))
  ([^protojure.internal.grpc.io.InputStream this]
   (let [ctx (.state this)
         buf (get-buffer ctx)]
     (or (some->> buf (.get) (bit-and 0xff) int)
         -1))))

;;--------------------------------------------------------------------------------------------
;; OutputStream
;;--------------------------------------------------------------------------------------------
(gen-class
 :name protojure.internal.grpc.io.OutputStream
 :extends java.io.OutputStream
 :prefix os-
 :state state
 :init init
 :constructors {[Object] []})

(defn- os-init [{:keys [ch max-frame-size] :or {max-frame-size 16384} :as options}]
  {:pre [(and (some? max-frame-size) (pos? max-frame-size))]}
  [[] {:ch ch :frame-size max-frame-size :buf (atom (ByteBuffer/allocate max-frame-size))}])

(defn- os-flush
  [^protojure.internal.grpc.io.OutputStream this]
  (let [{:keys [ch frame-size buf]} (.state this)
        ^ByteBuffer _buf @buf]
    (when (pos? (.position _buf))
      (async/>!! ch (.flip _buf))
      (reset! buf (ByteBuffer/allocate frame-size)))))

(defn- os-close
  [^protojure.internal.grpc.io.OutputStream this]
  (let [{:keys [ch]} (.state this)]
    (.flush this)
    (async/close! ch)))

(defn- os-write-int
  [^protojure.internal.grpc.io.OutputStream this b]
  (let [{:keys [buf]} (.state this)
        ^ByteBuffer _buf @buf]
    (when (zero? (.remaining _buf))
      (.flush this))
    (let [^ByteBuffer _buf @buf
          b (bit-and 0xff b)]
      (.put _buf (byte-array [b])))))
