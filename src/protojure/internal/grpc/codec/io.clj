;; Copyright © 2019 State Street Bank and Trust Company.  All rights reserved
;;
;; SPDX-License-Identifier: Apache-2.0

(ns protojure.internal.grpc.codec.io
  (:require [clojure.core.async :refer [<! >! <!! alt!! go go-loop] :as async]
            [clojure.tools.logging :as log])
  (:import (clojure.core.async.impl.channels ManyToManyChannel)))

(set! *warn-on-reflection* true)

(defn- take!-with-tmo [data-ch tmo]
  (if (some? tmo)
    (let [tmo-ch (async/timeout tmo)]
      (alt!!
        data-ch ([b] b)
        tmo-ch ([_] (throw (ex-info "Timeout" {:context "Timeout waiting for LPM bytes"})))))
    (<!! data-ch)))

;;--------------------------------------------------------------------------------------------
;; InputStream
;;--------------------------------------------------------------------------------------------
(gen-class
 :name protojure.internal.grpc.io.InputStream
 :extends java.io.InputStream
 :prefix is-
 :state state
 :init init
 :constructors {[Object] []}
 :exposes-methods {read parentRead})

(defn- is-init [{:keys [ch tmo]}]
  [[] {:ch ch :tmo tmo}])

(defn- is-available
  [^protojure.internal.grpc.io.InputStream this]
  (let [{:keys [ch]} (.state this)]
    (if-let [buf (.buf ^ManyToManyChannel ch)]
      (count buf)
      1)))                                                 ;; FIXME: estimate 1 byte if we can't tell?

(defn- is-read
  "Reads the next byte of data from the input stream. The value byte is returned as an int in the range 0 to 255.
  See InputStream for further details."
  ([^protojure.internal.grpc.io.InputStream this bytes offset len]
   (.parentRead this bytes offset len))
  ([^protojure.internal.grpc.io.InputStream this bytes]
   (.parentRead this bytes))
  ([^protojure.internal.grpc.io.InputStream this]
   (let [{:keys [ch tmo]} (.state this)]
     (if-let [b (take!-with-tmo ch tmo)]
       b
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

(defn- take-available
  "Creates a lazy-sequence of bytes representing what is currently available on the channel.  We
   block for regular data on the assumption that we are guaranteed that there is more data coming
   up until we see a :flush.  After a :flush, there may or may not be more data coming, so we
   test this with a poll! and opportunistically consume if it is available. This allows us to
   potentially coalesce multiple LPM frames into one DATA frame."
  [ch]
  (take-while some?
              (repeatedly
               #(loop [data (<!! ch)]
                  (when (some? data)
                    (if (= :flush data)
                      (recur (async/poll! ch))
                      data))))))

(defn- build-data-frame
  "Given a channel and a byte already received, accumulate all remaining data into a byte-array
   until we either consume all available bytes, fill the max-frame-size, or receive EOF."
  [ch first-byte max-frame-size]
  (->> (take-available ch)
       (take (dec max-frame-size))                          ;; -1 to account for first-byte
       (cons first-byte)
       (byte-array)))

(defn- bytes-to-frames
  "Creates byte-array frames from the stream of bytes available on the input channel"
  [input output max-frame-size]
  (go
    (try
      (loop []
        (when-let [data (<! input)]
          (when-not (= :flush data)                                   ;; drop :flush signals on the floor
            (>! output (build-data-frame input data max-frame-size)))
          (recur)))
      (catch Exception e
        (log/error e))
      (finally
        (async/close! output)))))

(defn- os-init-framed [{:keys [max-frame-size] :as options}]
  (let [bytes-ch (async/chan max-frame-size)
        frames-ch (:ch options)]
    (bytes-to-frames bytes-ch frames-ch max-frame-size)
    {:ch bytes-ch :framed? true}))

(defn- os-init [{:keys [ch max-frame-size] :as options}]
  (if (and (some? max-frame-size) (pos? max-frame-size))
    [[] (os-init-framed options)]
    [[] {:ch ch :framed? false}]))

(defn- os-flush
  "Sends a ':flush' signal to our framer when used in 'framed?=true' mode, NOP for streaming mode.
  N.B. The flush may or may not be result in an immediate write to the underlying sink since
  the framing layer may try to coalesce writes at its own discretion"
  [^protojure.internal.grpc.io.OutputStream this]
  (let [{:keys [ch framed?]} (.state this)]
    (when framed?
      (async/>!! ch :flush))))

(defn- os-close
  [^protojure.internal.grpc.io.OutputStream this]
  (let [{:keys [ch]} (.state this)]
    (async/close! ch)))

(defn- os-write-int
  [^protojure.internal.grpc.io.OutputStream this b]
  (let [{:keys [ch]} (.state this)]
    (async/>!! ch (bit-and b 0xFF))))
