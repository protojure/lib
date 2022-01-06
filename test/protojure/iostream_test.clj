;; Copyright © 2019 State Street Bank and Trust Company.  All rights reserved
;; Copyright © 2019-2022 Manetu, Inc.  All rights reserved
;;
;; SPDX-License-Identifier: Apache-2.0

(ns protojure.iostream-test
  (:require [clojure.test :refer :all]
            [clojure.core.async :as async]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.core.async :refer [<!!]])
  (:import (protojure.internal.io InputStream
                                  OutputStream)
           (java.nio ByteBuffer)))

(deftest check-input-eof
  (testing "Verify that our input stream reports EOF"
    (let [input (async/chan 64)
          stream (InputStream. {:ch input})]
      (async/close! input)
      (is (-> (.read stream) (= -1)))
      (is (-> (.available stream) (= 0))))))

(defn bufferify [x]
  (ByteBuffer/wrap (byte-array x)))

(deftest check-input-available
  (testing "Verify that our input stream reports available bytes properly"
    (let [input (async/chan 64)
          count 20
          stream (InputStream. {:ch input :buf (bufferify (repeat count 42))})]
      (is (-> (.available stream) (= count))))))

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
      (async/put! ch (ByteBuffer/wrap (byte-array (repeat len 42))))
      (.read stream output)
      (is (= (count output) len))
      (doseq [x output]
        (is (= x 42))))))

(defn- take-available [ch]
  (take-while some? (repeatedly #(<!! ch))))

(deftest check-output
  (testing "Verify that OutputStream functions properly"
    (let [repetitions 100
          phrase "test"
          msg (.getBytes (string/join (repeat repetitions phrase)))
          ch (async/chan (* repetitions (count phrase)))]
      (with-open [os (OutputStream. {:ch ch :max-frame-size (count phrase)})]
        (io/copy msg os))
      (let [result (take-available ch)]
        (is (-> result count (= repetitions)))
        (doseq [v (map (fn [^ByteBuffer x] (-> x .array (String.))) result)]
          (is (= phrase v)))))))