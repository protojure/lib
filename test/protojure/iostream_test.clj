;; Copyright © 2019 State Street Bank and Trust Company.  All rights reserved
;;
;; SPDX-License-Identifier: Apache-2.0

(ns protojure.iostream-test
  (:require [clojure.test :refer :all]
            [clojure.core.async :as async]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.core.async :refer [<!!]])
  (:import (protojure.internal.grpc.io InputStream
                                       OutputStream)))

(deftest check-input-eof
  (testing "Verify that our input stream reports EOF"
    (let [input (async/chan 64)
          stream (InputStream. {:ch input})]
      (async/close! input)
      (is (-> (.read stream) (= -1)))
      (is (-> (.available stream) (= 0))))))

(deftest check-input-available
  (testing "Verify that our input stream reports available bytes properly"
    (let [input (async/chan 64)
          stream (InputStream. {:ch input})
          count 20]
      (run! (partial async/put! input) (repeat count 42))
      (is (-> (.available stream) (= count))))))

(deftest check-bufferless-available
  (testing "Verify that a bufferless core.async channel returns '1' for (.available)"
    (let [input (async/chan)
          stream (InputStream. {:ch input})]
      (is (-> (.available stream) (= 1))))))

(deftest check-timeout
  (testing "Verify that our input stream's timeout mechanism works"
    (let [input (async/chan 64)
          stream (InputStream. {:ch input :tmo 100})]
      (is (thrown? clojure.lang.ExceptionInfo (.read stream))))))

(deftest check-array-read
  (testing "Verify that we can read an array in one call"
    (let [ch (async/chan 64)
          stream (InputStream. {:ch ch})
          len 20
          output (byte-array len)]
      (run! (partial async/put! ch) (repeat len 42))
      (.read stream output)
      (is (= (count output) len))
      (doseq [x output]
        (is (= x 42))))))

(defn- take-available [ch]
  (take-while some? (repeatedly #(<!! ch))))

(deftest check-streaming-output
  (testing "Verify that streaming-mode functions properly"
    (let [phrase "foobar"
          msg (.getBytes phrase)
          ch (async/chan 64)]
      (with-open [os (OutputStream. {:ch ch})]
        (io/copy msg os))
      (let [result (String. (byte-array (take-available ch)))]
        (is (= result phrase))))))

(deftest check-framed-output
  (testing "Verify that framed-mode functions properly"
    (let [repetitions 100
          phrase "test"
          msg (.getBytes (string/join (repeat repetitions phrase)))
          ch (async/chan (* repetitions (count phrase)))]
      (with-open [os (OutputStream. {:ch ch :max-frame-size (count phrase)})]
        (io/copy msg os))
      (let [result (map #(String. %) (take-available ch))]
        (is (-> result count (= repetitions)))
        (doseq [v result]
          (is (= phrase v)))))))