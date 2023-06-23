;; Copyright © 2019 State Street Bank and Trust Company.  All rights reserved
;; Copyright © 2019-2022 Manetu, Inc.  All rights reserved
;;
;; SPDX-License-Identifier: Apache-2.0

(ns protojure.pedestal-test
  (:require [clojure.test :refer :all]
            [clojure.core.async :as async :refer [go >! <!! >!!]]
            [io.pedestal.http :as http]
            [io.pedestal.http.body-params :as body-params]
            [promesa.exec :as p.exec]
            [protojure.pedestal.core :as core]
            [protojure.pedestal.interceptors.authz :as authz]
            [protojure.test.utils :as test.utils]
            [protojure.internal.io :as pio]
            [clj-http.client :as client]
            [clojure.java.io :as io])
  (:import [java.nio ByteBuffer]))

;;-----------------------------------------------------------------------------
;; Data
;;-----------------------------------------------------------------------------
(defonce test-svc (atom {}))

(defn- get-healthz [_]
  {:status 200
   :headers {"Content-Type" "application/text"}
   :trailers {"Meta" "test"}
   :body "OK"})

(defn- get-bytes [_]
  {:status   200
   :headers  {"Content-Type" "application/text"}
   :trailers {"Meta" "test"}
   :body     (byte-array [(byte 0x43)
                          (byte 0x6c)
                          (byte 0x6f)
                          (byte 0x6a)
                          (byte 0x75)
                          (byte 0x72)
                          (byte 0x65)
                          (byte 0x21)])})

(defn- get-edn [_]
  {:status   200
   :headers  {"Content-Type" "application/text"}
   :trailers {"Meta" "test"}
   :body     {:key "clojure is awesome"}})

(defn- echo-params [{{:keys [content]} :params}]
  {:status 200 :body content})

(defn- echo-body [{:keys [body]}]
  {:status 200 :body body})

(defn- json-content [{:keys [json-params] :as req}]
  {:status 200 :body json-params})

(defn- echo-async [{{:keys [content]} :params}]
  (let [ch (async/chan 1)]
    (go
      (>! ch (byte-array (map byte content)))
      (async/close! ch))
    {:status 200 :body ch}))

(defn- testdata-download [_]
  {:status 200 :body (io/as-file (io/resource "testdata.txt"))})

(defn- get-denied [_]
  ;; we should never get here
  {:status 200})

(defn routes [interceptors]
  [["/healthz" :get (conj interceptors `get-healthz)]
   ["/echo" :get (conj interceptors `echo-params)]
   ["/echo" :post (conj interceptors `echo-body)]
   ["/echo/async" :get (conj interceptors `echo-async)]
   ["/testdata" :get (conj interceptors `testdata-download)]
   ["/bytes" :get (conj interceptors `get-bytes)]
   ["/edn" :get (conj interceptors `get-edn)]
   ["/json" :post (conj interceptors `json-content)]
   ["/denied" :get (conj interceptors `get-denied)]])

(defn- sync-authorize?
  [{:keys [path-info] :as request}]
  ;; This authz predicate will always deny calls to the /denied endpoint.  A real implementation would probably
  ;; look at other criteria, such as the callers credentials
  (not= path-info "/denied"))

(defn- async-authorize?
  [{:keys [path-info] :as request}]
  ;; Identical to sync-authorize, except runs asynchronously
  (go
    (not= path-info "/denied")))

;;-----------------------------------------------------------------------------
;; Utilities
;;-----------------------------------------------------------------------------

(defn service-url
  [& rest]
  (apply str "http://localhost:" (:port @test-svc) rest))

(defn service-url-ssl
  [& rest]
  (apply str "https://localhost:" (:ssl-port @test-svc) rest))

(defn -check-throw
  [code f]
  (is (thrown? clojure.lang.ExceptionInfo
               (try
                 (f)
                 (catch Exception e
                   (let [{:keys [status]} (ex-data e)]
                     (is (= code status)))
                   (throw e))))))

(defmacro check-throw
  [code & body]
  `(-check-throw ~code #(do ~@body)))

;;-----------------------------------------------------------------------------
;; Fixtures
;;-----------------------------------------------------------------------------
(defn create-service []
  (let [port (test.utils/get-free-port)
        ssl-port (test.utils/get-free-port)
        interceptors [(body-params/body-params)
                      http/html-body
                      io.pedestal.http/json-body
                      (authz/interceptor nil sync-authorize?)
                      (authz/interceptor nil async-authorize?)]
        thread-pool   (p.exec/fixed-executor {:parallelism 64})
        desc {:env                     :prod
              ::http/routes            (into #{} (routes interceptors))
              ::http/port              port

              ::http/type              core/config
              ::http/chain-provider    core/provider
              ::core/thread-pool       thread-pool

              ::http/container-options {:ssl-port ssl-port
                                        ; keystore may be either string denoting file path (relative or
                                        ; absolute) or actual KeyStore instance
                                        :keystore (io/resource "https/keystore.jks")
                                        :key-password "password"}}]

    (let [server (http/create-server desc)]
      (http/start server)
      (swap! test-svc assoc :port port :ssl-port ssl-port :server server :thread-pool thread-pool))))

(defn destroy-service []
  (swap! test-svc (fn [x]
                    (-> x
                        (update :server http/stop)
                        (update :thread-pool #(.shutdown %))))))

(defn wrap-service [test-fn]
  (create-service)
  (test-fn)
  (destroy-service))

(use-fixtures :once wrap-service)

;;-----------------------------------------------------------------------------
;; Tests
;;-----------------------------------------------------------------------------
(deftest healthz-check
  (testing "Check that basic connectivity works"
    (is (-> (client/get (service-url "/healthz")) :body (= "OK")))))

(comment deftest ssl-check ;; FIXME: re-enable after we figure out why it fails on new JDK
         (testing "Check that SSL works"
           (is (-> (client/get (service-url-ssl "/healthz") {:insecure? true}) :body (= "OK")))))

(deftest query-param-check
  (testing "Check that query-parameters work"
    (is (-> (client/get (service-url "/echo") {:query-params {"content" "FOO"}}) :body (= "FOO")))))

(deftest body-check
  (testing "Check that response/request body work"
    (is (-> (client/post (service-url "/echo") {:body "BAR"}) :body (= "BAR")))))

(deftest async-check
  (testing "Check that async-body works"
    (is (-> (client/get (service-url "/echo/async") {:query-params {"content" "FOO"}}) :body (= "FOO")))))

(deftest file-download-check
  (testing "Check that we can download a file"
    (is (->> (client/get (service-url "/testdata")) :body (re-find #"testdata!") some?))))

(deftest bytes-check
  (testing "Check that bytes transfer correctly"
    (is (-> (client/get (service-url "/bytes")) :body (= "Clojure!")))))

(deftest edn-check
  (testing "Check that EDN format transfers"
    (is (-> (client/get (service-url "/edn")) :body clojure.edn/read-string (= {:key "clojure is awesome"})))))

(deftest notfound-check
  (testing "Check that a request for an invalid resource correctly propagates the error code"
    (check-throw 404 (client/get (service-url "/invalid")))))

(deftest read-check
  (testing "Check that bytes entered to channel are properly read from InputStream"
    (let [test-string "Hello"
          test-channel (async/chan 8096)
          in-stream (pio/new-inputstream {:ch test-channel})
          buff (byte-array 5)]
      (>!! test-channel (ByteBuffer/wrap (.getBytes test-string)))
      (async/close! test-channel)
      (.read in-stream buff 0 5)
      (is (= "Hello" (String. buff))))))

(deftest content-type-check
  (testing "Check that content-type key is set per [io.pedestal.http.request.map](https://github.com/pedestal/pedestal/blob/master/service/src/io/pedestal/http/request/map.clj) expectations"
    (is (as-> (client/post (service-url "/json") {:content-type :json :form-params {"content" "FOO"} :as :json-string-keys}) resp
          (= (:body resp) {"content" "FOO"})))))

(deftest denied-check
  (testing "Check that a request for an explicitly denied"
    (check-throw 401 (client/get (service-url "/denied")))))
