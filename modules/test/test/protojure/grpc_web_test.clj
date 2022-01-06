;; Copyright © 2019 State Street Bank and Trust Company.  All rights reserved
;; Copyright © 2019-2022 Manetu, Inc.  All rights reserved
;;
;; SPDX-License-Identifier: Apache-2.0

(ns protojure.grpc-web-test
  (:require [clojure.test :refer :all]
            [io.pedestal.test :refer [response-for]]
            [protojure.pedestal.core :as protojure.pedestal]
            [protojure.pedestal.interceptors.grpc-web :as grpc-web]
            [io.pedestal.http :as pedestal]
            [io.pedestal.http.body-params :as body-params]
            [example.types :as example]
            [protojure.protobuf :as pb]
            [clojure.data.codec.base64 :as b64]))

(defn grpc-echo [{:keys [body] :as request}]
  {:status   200
   :body     (example/pb->Money body)
   :trailers {"grpc-status" 0 "grpc-message" "Got it!"}})

(def interceptors [(body-params/body-params)
                   grpc-web/proxy])

(def routes [["/" :get (conj interceptors `grpc-echo)]])

(def service (let [service-params {:env                      :prod
                                   ::pedestal/routes         (into #{} routes)
                                   ::pedestal/type           protojure.pedestal/config
                                   ::pedestal/chain-provider protojure.pedestal/provider}]
               (:io.pedestal.http/service-fn (io.pedestal.http/create-servlet service-params))))

(deftest grpc-web-text-check
  (testing "Check that a round-trip GRPC request works"
    (let [input-msg (pb/->pb (example/new-Money {:currency_code (apply str (repeat 20 "foobar")) :units 42 :nanos 750000000}))]
      (is
       (=
        (with-out-str (pr (example/pb->Money input-msg)))
        (:body (response-for
                service
                :get "/"
                :headers {"Content-Type" "application/grpc-web-text"}
                :body (clojure.java.io/input-stream (b64/encode input-msg)))))))))

(deftest grpc-web-check
  (testing "Check that a round-trip GRPC request works"
    (let [input-msg (pb/->pb (example/new-Money {:currency_code (apply str (repeat 20 "foobar")) :units 42 :nanos 750000000}))]
      (is
       (=
        (with-out-str (pr (example/pb->Money input-msg)))
        (:body (response-for
                service
                :get "/"
                :headers {"Content-Type" "application/grpc-web"}
                :body (clojure.java.io/input-stream input-msg))))))))

(deftest grpc-web-proto-check
  (testing "Check that a round-trip GRPC request works"
    (let [input-msg (pb/->pb (example/new-Money {:currency_code (apply str (repeat 20 "foobar")) :units 42 :nanos 750000000}))]
      (is
       (=
        (with-out-str (pr (example/pb->Money input-msg)))
        (:body (response-for
                service
                :get "/"
                :headers {"Content-Type" "application/grpc-web+proto"}
                :body (clojure.java.io/input-stream input-msg))))))))

(deftest grpc-web-text-proto-check
  (testing "Check that a round-trip GRPC request works"
    (let [input-msg (pb/->pb (example/new-Money {:currency_code (apply str (repeat 20 "foobar")) :units 42 :nanos 750000000}))]
      (is
       (=
        (with-out-str (pr (example/pb->Money input-msg)))
        (:body (response-for
                service
                :get "/"
                :headers {"Content-Type" "application/grpc-web-text+proto"}
                :body (clojure.java.io/input-stream (b64/encode input-msg)))))))))

(deftest grpc-web-no-header-match-check
  (testing "Check that a round-trip GRPC request works"
    (let [input-msg (pb/->pb (example/new-Money {:currency_code (apply str (repeat 20 "foobar")) :units 42 :nanos 750000000}))]
      (is
       (=
        (with-out-str (pr (example/pb->Money input-msg)))
        (:body (response-for
                service
                :get "/"
                :headers {"Content-Type" "application/grpc"}
                :body (clojure.java.io/input-stream input-msg))))))))
