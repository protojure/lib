;; Copyright © 2019 State Street Bank and Trust Company.  All rights reserved
;;
;; SPDX-License-Identifier: Apache-2.0

(ns protojure.protobuf-test
  (:require [clojure.test :refer :all]
            [clojure.core.async :refer [<!! >!! <! >! go] :as async]
            [protojure.protobuf.serdes :as serdes]
            [protojure.protobuf :refer [->pb]]
            [protojure.grpc.codec.lpm :as lpm]
            [protojure.grpc.codec.compression :as compression]
            [protojure.test.utils :refer [data-equal?]]
            [promesa.core :as p]
            [example.types :as example])
  (:import (com.google.protobuf CodedOutputStream
                                CodedInputStream)
           (java.io ByteArrayInputStream)
           (org.apache.commons.io.input CloseShieldInputStream)
           (org.apache.commons.io.output CloseShieldOutputStream))
  (:refer-clojure :exclude [resolve]))

;;-----------------------------------------------------------------------------
;; Helper functions
;;-----------------------------------------------------------------------------

(defn- fns [type]
  (mapv #(clojure.core/resolve (symbol "protojure.protobuf.serdes" (str % type)))
        ["size-" "write-" "cis->"]))

(defn- resolve-fns [type]
  (let [[sizefn writefn parsefn] (fns type)]
    {:sizefn sizefn :writefn writefn :parsefn parsefn}))

(defn- pbverify
  "End to end serdes testing for a specific message"
  [newf pb->f data]
  (let [input (newf data)
        output (-> input ->pb pb->f)]
    (is (data-equal? input output))))

(defn- with-buffer
  "Invokes 'f' with a fully formed buffered output-stream and returns the bytes"
  [len f]
  (let [buf (byte-array len)
        os (CodedOutputStream/newInstance buf)]
    (f os)
    (.flush os)
    buf))

(defn- write [sizefn writefn tag value]
  (let [len (sizefn tag value)]
    (with-buffer len (partial writefn tag value))))

(defn- write-embedded [tag item]
  (write serdes/size-embedded serdes/write-embedded tag item))

(defn- write-repeated [sizefn writefn tag items]
  (let [len (serdes/size-repeated sizefn tag items)]
    (with-buffer len (partial serdes/write-repeated writefn tag items))))

(defn- parse [^bytes buf readfn]
  (let [is (CodedInputStream/newInstance buf)]
    (.readTag is)
    (readfn is)))

(defn- parse-repeated [^bytes buf readfn packable? tag]
  (let [is (CodedInputStream/newInstance buf)
        f (if packable? (partial serdes/cis->packablerepeated tag) serdes/cis->repeated)]
    (serdes/tag-map
     (fn [tag index]
       [index (f readfn is)])
     is)))

(defn- byte-string [x] (byte-array (mapv byte x)))

(defn- test-repeated [data]
  (-> (byte-array data)
      (example/pb->SimpleRepeated)
      (:data)))

(defn- repeat-num
  "Create an range of 'n' contiguous values from 'input'"
  [n input]
  (take n (iterate inc input)))

;;-----------------------------------------------------------------------------
;; Test data
;;-----------------------------------------------------------------------------

(def tag 0x80)                                                ;; random tag to use

(def test-msg {:currency_code "USD" :units 42 :nanos 750000000})
(def long-test-msg (update test-msg :currency_code (fn [x] (apply str (repeat 20 x)))))

(def int-scalars
  ["Enum"
   "Fixed32"
   "Fixed64"
   "Int32"
   "Int64"
   "SFixed32"
   "SFixed64"
   "SInt32"
   "SInt64"
   "UInt32"
   "UInt64"])

(def float-scalars
  ["Float"
   "Double"])

(def _test-data
  ;;------------------------------------------------------------------------------------
  ;; types          input                   default           packable?        repeatfn
  ;;------------------------------------------------------------------------------------
  [[int-scalars     42                      0                 true             repeat-num]
   [float-scalars   42.0                    0.0               true             repeat-num]
   [["Bool"]        true                    false             true             repeat]
   [["String"]      "hello"                 ""                false            repeat]
   [["Bytes"]       (byte-string "hello")   (byte-array 0)    false            repeat]])

(def test-data
  (flatten
   (for [[types input default packable? repeatfn] _test-data]
     (map (fn [type] {:type type :input input :default default :packable? packable? :repeatfn repeatfn}) types))))

;;-----------------------------------------------------------------------------
;; Validation helpers
;;-----------------------------------------------------------------------------

(defn- validate-e2e
  "validate that we can do a complete end-to-end serialize->deserialize cycle"
  [{:keys [type input]}]
  (let [{:keys [sizefn writefn parsefn]} (resolve-fns type)
        output (-> (write sizefn writefn tag input)
                   (parse parsefn))]

    (is (data-equal? input output))))

(defn- validate-optimizer
  "validate optimizer behavior. 'input' items should _never_ be elided, thus
  they should always generate a positive length calculation.  'default' fields
  however, are fields that are carrying default values.  The optimizer should
  detect this and elide them from the wire, resulting in a 0 length calc.  For
  good measure, we also fire up a (writefn) operation to a nil output stream.
  A correct functioning optimizer will elide the write, resulting in no errors
  even despite our bogus stream."
  [{:keys [type input default]}]
  (let [{:keys [writefn sizefn]} (resolve-fns type)]
    (is (pos? (sizefn tag input)))
    (is (zero? (sizefn tag default)))
    (is (pos? (sizefn tag {:optimize false} default)))
    (writefn tag default nil)))

(defn- validate-repeated
  [{:keys [type input packable? repeatfn]}]
  (let [{:keys [sizefn writefn parsefn]} (resolve-fns type)
        items (vec (repeatfn 10 input))
        output (-> (write-repeated sizefn writefn tag items)
                   (parse-repeated parsefn packable? tag)
                   (get tag))]

    (is (data-equal? items output))
    (is (zero? (serdes/size-repeated sizefn tag [])))))

;; We add a silly codec named "mycustom" that does nothing.  We use the CloseShieldXXX family
;; of proxy stream classes so that we pass the IO through, but bury the (.close) operation. This
;; codec is only useful for validating that a custom-codec actually works.
(def custom-codecs
  (assoc compression/builtin-codecs
         "mycustom" {:input #(CloseShieldInputStream. %) :output #(CloseShieldOutputStream. %)}))

(defn- validate-lpm-msg
  [input-ch output-ch input]
  (>!! input-ch input)
  (let [output (<!! output-ch)]
    (is (data-equal? input output))))

(defn- validate-lpm
  [msg content-coding]
  (let [input (async/chan 64)
        wire (async/chan 16384)
        output (async/chan 64)
        options (-> {:codecs custom-codecs}
                    (cond-> (some? content-coding) (assoc :content-coding content-coding)))
        tasks (p/all [(lpm/encode example/new-Money input wire options)
                      (lpm/decode example/pb->Money wire output options)])]

    (run! (partial validate-lpm-msg input output) (repeat 10 msg))
    (async/close! input)
    @tasks))

(defn- validate-bad-codec
  [msg content-coding]
  (is (thrown? clojure.lang.ExceptionInfo (validate-lpm msg content-coding))))

;;-----------------------------------------------------------------------------
;; Tests
;;-----------------------------------------------------------------------------

(deftest raw-e2e-test
  (testing "Test each type round trip serialize->deserialize"
    (run! validate-e2e test-data)))

(deftest optimizer-test
  (testing "Check that the optimizer skips default values"
    (run! validate-optimizer test-data)))

(deftest pb-e2e-test
  (testing "End-to-end testing by processing arbitrary PB type"
    (pbverify example/new-Money
              example/pb->Money
              test-msg)))

(deftest repeated-test
  (testing "Check that repeated values are handled properly"
    (run! validate-repeated test-data)))

;; Represent a 'repeated int32' wire representation in both
;; packed and unpacked format.  For more details, see:
;; https://developers.google.com/protocol-buffers/docs/encoding#packed
(deftest packed-repeated-test
  (testing "Testing repeated field decoding of packed structures"
    (let [result (test-repeated [0xA 3 21 22 23 0xA 3 24 25 26])] ;; send the data in two chunks
      (is (= (count result) 6))
      (is (data-equal? result [21 22 23 24 25 26])))))

(deftest packed-repeated-varint-test
  (testing "Testing repeated field decoding of variable length values"
    (let [result (test-repeated [0xA 0x05 0x27 0x00 0xf4 0x06 0x01])]
      (is (= (count result) 4))
      (is (data-equal? result [39 0 884 1])))))

(deftest unpacked-repeated-test
  (testing "Testing repeated field decoding of unpacked structures"
    (let [result (test-repeated [0x8 1 0x8 2 0x8 3])]
      (is (= (count result) 3))
      (is (data-equal? result [1 2 3])))))

(deftest map-test
  (testing "Test map serialization"
    (pbverify example/new-AllThingsMap
              example/pb->AllThingsMap
              {:s "hello"
               :i 42
               :mSimple {"k1" 42 "k2" 43}
               :mComplex {"k1" {:s "v1"} "k2" {:s "v2"}}
               :sSimple {:s "simple"}})))

(deftest embedded-test
  (testing "Verify that we can embed a message in a coded stream"
    (let [input (example/new-Money test-msg)
          output (-> (write-embedded tag input)
                     (parse example/ecis->Money))]
      (is (data-equal? input output)))))

(deftest embedded-nil-test
  (testing "Check that embedded but unset messages are handled properly"
    (is (zero? (serdes/size-embedded tag nil)))
    (serdes/write-embedded tag nil nil)))

(deftest grpc-lpm-test
  (testing "Verify that we can round-trip through the LPM logic with each compression type"
    (let [codecs [nil "identity" "gzip" "deflate" "snappy" "mycustom"]]
      (run! (partial validate-lpm long-test-msg) codecs)
      (run! (partial validate-lpm test-msg) codecs))))

(deftest grpc-timeout-test
  (testing "Verify that we correctly timeout on a stalled decode"
    (let [input (async/chan 16384)
          output (async/chan 64)]
      (run! (fn [x] (async/put! input (byte x))) [0 0 0 0 4])
      (is (thrown? java.util.concurrent.ExecutionException @(lpm/decode example/pb->Money input output {:tmo 100}))))))

(deftest grpc-bad-codec
  (testing "Verify that we reject invalid codec types"
    (run! (partial validate-bad-codec test-msg) ["bad-codec" 42 {}])))

(deftest grpc-bad-decode
  (testing "Verify that we error decoding an invalid message"
    (let [input (async/chan 16384)
          output (async/chan 64)
          pipeline (lpm/decode example/pb->Money input output {})]

      (go
        (doseq [b (repeatedly 256000 #(unchecked-byte (rand-int 256)))]
          (>! input b))
        (async/close! input))

      (loop []
        (if-let [_ (<!! output)]
          (recur)
          (is (thrown? Exception @pipeline)))))))

(deftest grpc-bad-encode
  (testing "Verify that we error encoding an invalid message"
    (let [input (async/chan 64)
          output (async/chan 16384)
          pipeline (lpm/encode example/new-Money input output {})]

      (go
        (>!! input {:nanos "bad data"})
        (async/close! input))

      (loop []
        (if-let [_ (<!! output)]
          (recur)
          (is (thrown? Exception @pipeline)))))))