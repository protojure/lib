(ns protojure.pedestal.interceptors.grpc-web-test
  (:require [clojure.test :refer :all])
  (:require [protojure.pedestal.interceptors.grpc-web :refer [read-n]]
            [clojure.core.async :as async]))

(deftest read-n-test
  (testing "test that read-n reads the appropriate values and returns final on final"
    (let [test-ch (async/chan 10)]
      (doseq [n [1 2 3 4]]
        (async/>!! test-ch n))
      (async/close! test-ch)
      (let [result (async/<!! (read-n test-ch 3))]
        (is (= result [false [1 2 3]]))
        (is (= [true [4]] (async/<!! (read-n test-ch 3))))))))
