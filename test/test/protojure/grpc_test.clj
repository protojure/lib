;; Copyright © 2019 State Street Bank and Trust Company.  All rights reserved
;; Copyright © 2019-2022 Manetu, Inc.  All rights reserved
;;
;; SPDX-License-Identifier: Apache-2.0

(ns protojure.grpc-test
  (:require [clojure.test :refer :all]
            [clojure.string :as string]
            [clojure.core.async :refer [<!! >!! <! >! go go-loop] :as async]
            [promesa.core :as p]
            [io.pedestal.http :as pedestal]
            [io.pedestal.http.body-params :as body-params]
            [io.pedestal.interceptor :refer [interceptor]]
            [io.pedestal.interceptor.chain :refer [terminate]]
            [taoensso.timbre :as log]
            [taoensso.timbre.appenders.core :as appenders]
            [taoensso.timbre.tools.logging :refer [use-timbre]]
            [clojure.data.generators :as gen]
            [clj-uuid :as uuid]
            [protojure.pedestal.core :as protojure.pedestal]
            [protojure.pedestal.routes :as pedestal.routes]
            [protojure.grpc.client.api :as grpc]
            [protojure.grpc.client.providers.http2 :as grpc.http2]
            [protojure.internal.grpc.client.providers.http2.jetty :as jetty-client]
            [protojure.grpc.client.utils :as client.utils]
            [protojure.grpc.status :as grpc.status]
            [protojure.test.utils :as test.utils :refer [data-equal?]]
            [example.types :as example]
            [example.hello :refer [new-HelloRequest pb->HelloRequest new-HelloReply pb->HelloReply]]
            [example.hello.Greeter :as greeter]
            [protojure.test.grpc.TestService.server :as test.server]
            [protojure.test.grpc.TestService.client :as test.client]
            [protojure.internal.grpc.client.providers.http2.jetty :as jetty]
            [crypto.random :as crypto]
            [criterium.core :as criterium])
  (:import [java.nio ByteBuffer])
  (:refer-clojure :exclude [resolve]))

(log/set-config! {:level :error
                  :ns-whitelist ["protojure.*"]
                  :appenders {:println (appenders/println-appender {:stream :auto})}})

(use-timbre)

;;-----------------------------------------------------------------------------
;; Data
;;-----------------------------------------------------------------------------
(defonce test-env (atom {}))

(def test-trailers {"foo" "baz"
                    "bar" "bat"})

(def closedetect-ch (async/chan 1))

(def bandwidth-payload (crypto/bytes 1000000))

(def upload-types #{:mode-bidi :mode-upload})
(def download-types #{:mode-bidi :mode-download})

;;-----------------------------------------------------------------------------
;; Mock endpoint
;;-----------------------------------------------------------------------------
(defn- echo [{:keys [body] :as request}]
  {:status 200 :body body})

(defn- get-trailers [_]
  {:status 200 :trailers test-trailers :body "OK"})

(defn- get-async [_]
  (let [body-ch (async/chan 1)
        trailers-ch (async/promise-chan)]
    (go
      (dotimes [_ 10]
        (<! (async/timeout 10))
        (>! body-ch (.getBytes "OK")))
      (async/close! body-ch)
      (<! (async/timeout 10))
      (>! trailers-ch test-trailers))
    {:status 200 :trailers trailers-ch :body body-ch}))

(defn- grpc-echo [{:keys [body] {:strs [grpc-encoding]} :headers :as request}]
  {:status   200
   :headers  {"grpc-encoding" grpc-encoding
              "content-type"  "application/grpc+proto"}
   :body     body
   :trailers {"grpc-status" 0 "grpc-message" "Got it!"}})

(defn- grpc-missing-trailers [{:keys [body] :as request}]
  {:status 200 :body "OK"})

(defn- grpc-failing-status [{:keys [body] :as request}]
  {:status 200 :body "Permission Denied!" :trailers {"grpc-status" 7 "grpc-message" "Permission Denied"}})

(defn- grpc-invalid-status [{:keys [body] :as request}]
  {:status 200 :body "I'm not valid!" :trailers {"grpc-status" "bad"}})

(defn- grpc-bad-encoding [{:keys [body] :as request}]
  {:status 200
   :headers {"grpc-encoding" "bad-codec"
             "content-type" "application/grpc+proto"}
   :body (byte-array [1 0 0 0 4 0 0 0 1])
   :trailers {"grpc-status" 0 "grpc-message" "BAR"}})

(defn generic-mock-routes [interceptors]
  [["/echo" :post (conj interceptors `echo)]
   ["/trailers" :get (conj interceptors `get-trailers)]
   ["/async" :get (conj interceptors `get-async)]
   ["/protojure.http2-test/Echo" :post (conj interceptors `grpc-echo)]
   ["/protojure.http2-test/MissingTrailers" :post (conj interceptors `grpc-missing-trailers)]
   ["/protojure.http2-test/FailingStatus" :post (conj interceptors `grpc-failing-status)]
   ["/protojure.http2-test/InvalidStatus" :post (conj interceptors `grpc-invalid-status)]
   ["/protojure.http2-test/BadEncoding" :post (conj interceptors `grpc-bad-encoding)]])

;;-----------------------------------------------------------------------------
;; "Greeter" service endpoint
;;-----------------------------------------------------------------------------
(deftype Greeter []
  greeter/Service
  (SayHello
    [this {{:keys [name]} :grpc-params :as request}]
    {:status 200
     :body {:message (str "Hello, " name)}})
  (SayRepeatHello
    [this {{:keys [name]} :grpc-params :as request}]
    (let [resp-chan (:grpc-out request)]
      (go
        (dotimes [_ 3]
          (>! resp-chan {:message (str "Hello, " name)}))
        (async/close! resp-chan))
      {:status 200
       :body resp-chan}))
  (SayHelloOnDemand
    [this {:keys [grpc-params] :as request}]
    (let [out-chan (:grpc-out request)]
      (go-loop [name (:name (<! grpc-params))]
        (if name
          (do
            (>! out-chan {:message (str "Hello, " name)})
            (recur (:name (<! grpc-params))))
          (async/close! out-chan)))
      {:status 200
       :body out-chan}))
  (SayHelloError
    [this req]
    {:status 200
     :body "This isn't a protobuf message"})
  (SayNil
    [this req]
    (grpc.status/error :unauthenticated)))

;;-----------------------------------------------------------------------------
;; TestService service endpoint
;;-----------------------------------------------------------------------------
(deftype TestService []
  test.server/Service
  (FlowControl
    [_ {{:keys [count payload-size]} :grpc-params :keys [grpc-out] :as request}]
    (go
      (dotimes [i count]
        (>! grpc-out {:id i :data (byte-array (repeatedly payload-size gen/byte))}))
      (async/close! grpc-out))
    {:body grpc-out})

  (ClientCloseDetect
    [_ {{:keys [id]} :grpc-params :keys [grpc-out close-ch] :as request}]
    (go
      (<! close-ch)
      (>! closedetect-ch id)
      (async/close! grpc-out))
    {:body grpc-out})

  (ServerCloseDetect
    [_ {:keys [grpc-out close-ch] :as request}]
    (go
      (<! (async/timeout 10000))
      (log/trace "client closing")
      (async/close! grpc-out))
    {:body grpc-out})

  (Metadata
    [_ request]
    (let [auth (get-in request [:headers "authorization"])]
      {:body {:msg (str "Hello, " auth)}}))

  (ShouldThrow
    [_ request]
    (throw (ex-info "This is supposed to fail" {})))

  (Async
    [_ request]
    (go {:body {:msg "Hello, Async"}}))

  (AllEmpty
    [_ request]
    {:body {}})

  (AsyncEmpty
    [_ {:keys [grpc-out]}]
    (async/close! grpc-out)
    {:body grpc-out})

  (ReturnError
    [_ {{:keys [status message]} :grpc-params}]
    {:grpc-status status :grpc-message message})

  (ReturnErrorStreaming
    [_ {:keys [grpc-out] {:keys [status message]} :grpc-params}]
    (let [trailers (async/promise-chan)]
      (go
        (<! (async/timeout 500))
        (async/close! grpc-out)
        (>! trailers {:grpc-status status :grpc-message message}))
      {:body grpc-out :trailers trailers}))

  (BandwidthTest
    [_ {{:keys [mode]} :grpc-params}]
    (cond-> {}
      (contains? download-types mode) (assoc-in [:body :data] bandwidth-payload))))

(defn- greeter-mock-routes [interceptors]
  (pedestal.routes/->tablesyntax {:rpc-metadata greeter/rpc-metadata
                                  :interceptors interceptors
                                  :callback-context (Greeter.)}))

(defn- testservice-mock-routes [interceptors]
  (pedestal.routes/->tablesyntax {:rpc-metadata test.server/rpc-metadata
                                  :interceptors interceptors
                                  :callback-context (TestService.)}))

(defn routes [interceptors]
  (concat
   (generic-mock-routes interceptors)
   (greeter-mock-routes interceptors)
   (testservice-mock-routes interceptors)))

;;-----------------------------------------------------------------------------
;; Utilities
;;-----------------------------------------------------------------------------
(defn service-url
  [& rest]
  (apply str "http://localhost:" (:port @test-env) rest))

(defn- run!-first [coll f]
  (run! f coll))

(defn- grpc-connect
  ([] (grpc-connect (:port @test-env)))
  ([port]
   @(grpc.http2/connect {:uri (str "http://localhost:" port) :content-coding "gzip"})))

(defn -check-throw
  [code f]
  (is (thrown? java.util.concurrent.ExecutionException
               (try
                 (f)
                 (catch Exception e
                   (let [{:keys [status]} (.data (ex-cause e))]
                     (is (= code status)))
                   (throw e))))))

(defmacro check-throw
  [code & body]
  `(-check-throw ~code #(do ~@body)))

;;-----------------------------------------------------------------------------
;; Scaletest Assemblies
;;-----------------------------------------------------------------------------
(defn- scaletest-xmit [{:keys [input]}]
  (>!! input {:name "World"}))

(defn- scaletest-recv [{:keys [output]}]
  (let [result (<!! output)]
    (is (data-equal? result {:message "Hello, World"}))))

(defn- scaletest-close [{:keys [input]}]
  (async/close! input))

(defn- scaletest-wait [{:keys [request]}]
  (let [{:keys [status]} @request]
    (is (= status 0))))

(defn- scaletest-disconnect [{:keys [client]}]
  (grpc/disconnect client))

;;-----------------------------------------------------------------------------
;; Streaming Scaletest
;;-----------------------------------------------------------------------------
(defn- streaming-scaletest-invoke
  [client-fn]
  (let [input (async/chan 1)
        output (async/chan 1)
        client (client-fn)
        desc {:service "example.hello.Greeter"
              :method "SayHelloOnDemand"
              :input {:f new-HelloRequest :ch input}
              :output {:f pb->HelloReply :ch output}}]

    {:input input
     :output output
     :client client
     :request (grpc/invoke client desc)}))

(defn streaming-scaletest
  ([parallelism] (streaming-scaletest parallelism (partial identity (:grpc-client @test-env))))
  ([parallelism client-fn]
   (doto (doall (repeatedly parallelism #(streaming-scaletest-invoke client-fn)))
     (run!-first scaletest-xmit)
     (run!-first scaletest-recv)
     (run!-first scaletest-close)
     (run!-first scaletest-wait))))

;;-----------------------------------------------------------------------------
;; Client Scaletest
;;-----------------------------------------------------------------------------
(defn client-scaletest [parallelism]
  (doto (streaming-scaletest parallelism grpc-connect)
    (run!-first scaletest-disconnect)))

;;-----------------------------------------------------------------------------
;; Unary Scaletest
;;-----------------------------------------------------------------------------
(defn- unary-scaletest-invoke []
  (let [input (async/chan 1)
        output (async/chan 1)
        client (:grpc-client @test-env)
        desc {:service "example.hello.Greeter"
              :method "SayHello"
              :input {:f new-HelloRequest :ch input}
              :output {:f pb->HelloReply :ch output}}]

    (>!! input {:name "World"})
    (async/close! input)

    {:input input
     :output output
     :client client
     :request (grpc/invoke client desc)}))

(defn unary-scaletest [parallelism]
  (doto (doall (repeatedly parallelism unary-scaletest-invoke))
    (run!-first scaletest-recv)
    (run!-first scaletest-wait)))

;;------------------------------------------------------------------------------------
;; Synchronous send-request helpers
;;------------------------------------------------------------------------------------
(defn- receive-metadata [ch]
  (p/create
   (fn [resolve reject]
     (go-loop [response {}]
       (if-let [data (<! ch)]
         (recur (merge response data))
         (resolve response))))))

(defn concat-byte-arrays [& byte-arrays]
  (when (not-empty byte-arrays)
    (let [total-size (reduce + (map count byte-arrays))
          result     (byte-array total-size)
          bb         (ByteBuffer/wrap result)]
      (doseq [ba byte-arrays]
        (.put bb ba))
      result)))

(defn ->bytes [^ByteBuffer src]
  (let [len (.remaining src)
        dst (byte-array len)]
    (.get src dst)
    dst))

(defn byte-conj
  [coll buf]
  (let [b (->bytes buf)]
    (concat-byte-arrays coll b)))

(defn- receive-body [ch]
  (p/create
   (fn [resolve reject]
     (go-loop [body (byte-array 0)]
       (if-let [data (<! ch)]
         (recur (byte-conj body data))
         (resolve body))))))

(defn send-request-sync
  [context {:keys [body] :as request}]
  (let [ic (async/chan 1)
        mc (async/chan 32)
        oc (async/chan 16384)]

    (go
      (when (some? body)
        (>! ic (ByteBuffer/wrap body)))
      (async/close! ic))

    @(-> (p/all [(-> (jetty-client/send-request context (assoc request :input-ch ic :meta-ch mc :output-ch oc))
                     (p/then (partial jetty/transmit-data-frames ic)))
                 (receive-metadata mc)
                 (receive-body oc)])
         (p/then (fn [[_ response body]]
                   (assoc response :body body))))))

;;-----------------------------------------------------------------------------
;; Security Interceptor
;;-----------------------------------------------------------------------------
(defn- permission-denied
  "Terminates the context with a 401 status"
  [context]
  (-> context
      (assoc :response {:status 401})
      (terminate)))

(defn- deny-streamer
  "This interceptor will always deny calls to the DeniedStreamer interface"
  [{{:keys [path-info]} :request :as context}]
  (cond-> context
    (= path-info "/protojure.test.grpc.TestService/DeniedStreamer") permission-denied))

(def reject-denied-streamer
  (interceptor
   {:name ::require-auth :enter deny-streamer}))

;;-----------------------------------------------------------------------------
;; Fixtures
;;-----------------------------------------------------------------------------
(defn create-service []
  (let [port (test.utils/get-free-port)
        interceptors [(body-params/body-params)
                      pedestal/html-body
                      reject-denied-streamer]
        server-params {:env                      :prod
                       ::pedestal/routes         (into #{} (routes interceptors))
                       ::pedestal/port           port

                       ::pedestal/type           protojure.pedestal/config
                       ::pedestal/chain-provider protojure.pedestal/provider}
        client-params {:port port :idle-timeout -1}]

    (let [server (test.utils/start-pedestal-server server-params)
          client @(jetty-client/connect client-params)
          grpc-client (grpc-connect port)]
      (swap! test-env assoc :port port :server server :client client :grpc-client grpc-client))))

(defn destroy-service []
  (swap! test-env update :grpc-client grpc/disconnect)
  (swap! test-env update :client jetty-client/disconnect)
  (swap! test-env update :server pedestal/stop))

(defn wrap-service [test-fn]
  (create-service)
  (test-fn)
  (destroy-service))

(use-fixtures :each wrap-service)

;;-----------------------------------------------------------------------------
;; Tests
;;-----------------------------------------------------------------------------
(deftest bad-address-check
  (testing "Check that we behave rationally with a bad address"
    (is (thrown? java.util.concurrent.ExecutionException
                 @(jetty-client/connect {:host "bad.example.com" :port 80})))
    (is (thrown? java.util.concurrent.ExecutionException
                 @(jetty-client/connect {:host "localhost" :port 1})))))

(deftest echo-check
  (testing "Check that basic connectivity works"
    (let [client (:client @test-env)
          input (.getBytes "ping")
          result (send-request-sync client {:method "POST" :url (service-url "/echo") :body input})
          output (:body result)]
      (is (-> result :status (= 200)))
      (is (data-equal? input output)))))

(deftest trailers-check
  (testing "Check that trailers are delivered"
    (let [client (:client @test-env)
          result (send-request-sync client {:url (service-url "/trailers")})
          trailers (:trailers result)]
      (is (-> result :status (= 200)))
      (is (data-equal? trailers test-trailers)))))

(deftest async-check
  (testing "Check that async data is delivered"
    (let [client (:client @test-env)
          result (send-request-sync client {:url (service-url "/async")})
          trailers (:trailers result)]
      (is (-> result :status (= 200)))
      (is (-> result :body String. (= (string/join (repeat 10 "OK")))))
      (is (data-equal? trailers test-trailers)))))

(deftest basic-grpc-check
  (testing "Check that a round-trip GRPC request works"
    (let [input-msg {:currency_code (apply str (repeat 20 "foobar")) :units 42 :nanos 750000000}
          input (async/chan 16)
          output (async/chan 16)
          client (:grpc-client @test-env)]

      (go
        (>! input input-msg)
        (async/close! input))

      @(-> (grpc/invoke client {:service "protojure.http2-test"
                                :method "Echo"
                                :input {:f example/new-Money :ch input}
                                :output {:f example/pb->Money :ch output}})
           (p/then (fn [{:keys [status] :as result}]
                     (is (= status 0))
                     (let [output-msgs (take-while some? (repeatedly #(<!! output)))]
                       (is (-> output-msgs count (= 1)))
                       (is (data-equal? input-msg (first output-msgs))))))))))

(deftest unary-grpc-check
  (testing "Check that a round-trip unary GRPC request works"
    (let [input (async/chan 1)
          output (async/chan 16)
          client (:grpc-client @test-env)
          desc {:service "example.hello.Greeter"
                :method "SayHello"
                :input {:f new-HelloRequest :ch input}
                :output {:f pb->HelloReply :ch output}}]

      @(-> (client.utils/send-unary-params input {:name "World"})
           (p/then (fn [_] (client.utils/invoke-unary client desc output)))
           (p/then (fn [{:keys [message] :as result}]
                     (is (= message "Hello, World"))))))))

;;Note there are qualitative differences between this nil & error check and the below grpc-failing-status-check test
;; this test is run against an endpoint registrered in the pedestal interceptor stack, and so exercises
;; protojure.pedestal.interceptors.grpc pedestal interceptor, where the aforementioned below test does not
(deftest nil-check
  (testing "Check that nil body and grpc-status error code propagates to client"
    (let [client (:grpc-client @test-env)
          input (async/chan 16)
          output (async/chan 16)
          desc {:service "example.hello.Greeter"
                :method "SayNil"
                :input {:f example/new-Money :ch input}
                :output {:f example/pb->Money :ch output}}]
      @(client.utils/send-unary-params input nil)
      (check-throw 16
                   @(client.utils/invoke-unary client desc output)))))

(deftest streaming-grpc-check
  (testing "Check that a round-trip streaming GRPC request works"
    (let [repetitions 50
          input (async/chan repetitions)
          output (async/chan repetitions)
          client (:grpc-client @test-env)
          desc {:service "example.hello.Greeter"
                :method "SayHelloOnDemand"
                :input {:f new-HelloRequest :ch input}
                :output {:f pb->HelloReply :ch output}}]

      (async/onto-chan input (repeat repetitions {:name "World"}))

      @(-> (grpc/invoke client desc)
           (p/then (fn [{:keys [status]}]
                     (is (= status 0))
                     (let [result (take-while some? (repeatedly #(<!! output)))]
                       (is (-> result count (= repetitions)))
                       (is (every? (partial data-equal? {:message "Hello, World"}) result)))))))))

(deftest grpc-async-check
  (testing "Check that an async GRPC request works"
    (streaming-scaletest 1)))

(def parallelism 100)

(deftest grpc-streaming-scale-check
  (testing "Check that parallel streaming GRPC requests may scale"
    (streaming-scaletest parallelism)))

(deftest grpc-client-scale-check
  (testing "Check that GRPC requests may scale when arriving from distinct clients"
    (client-scaletest parallelism)))

(deftest grpc-unary-scale-check
  (testing "Check that parallel unary GRPC requests may scale"
    (unary-scaletest parallelism)))

(deftest bad-grpc-check
  (testing "Check that a bogus GRPC request throws an exception"
    (let [client (:grpc-client @test-env)]
      (is (thrown? java.util.concurrent.ExecutionException
                   @(grpc/invoke client {:service "protojure.unknown-service"
                                         :method "UnknownMethod"}))))))

(deftest bad-grpc-trailers-check
  (testing "Check that a bogus GRPC status response throws an exception"
    (let [client (:grpc-client @test-env)]
      (is (thrown? java.util.concurrent.ExecutionException
                   @(grpc/invoke client {:service "protojure.http2-test"
                                         :method "MissingTrailers"}))))))

(deftest grpc-failing-status-check
  (testing "Check that a failing GRPC status response throws an exception"
    (let [client (:grpc-client @test-env)]
      (is (thrown? java.util.concurrent.ExecutionException
                   @(grpc/invoke client {:service "protojure.http2-test"
                                         :method "FailingStatus"}))))))

(deftest grpc-invalid-status-check
  (testing "Check that an invalid GRPC status response throws an exception"
    (let [client (:grpc-client @test-env)]
      (is (thrown? java.util.concurrent.ExecutionException
                   @(grpc/invoke client {:service "protojure.http2-test"
                                         :method "InvalidStatus"}))))))

(deftest bad-grpc-encoding-check
  (testing "Check that a GRPC status response with a bad encoding-type throws an exception"
    (let [client (:grpc-client @test-env)
          output (async/chan 16)]
      (is (thrown? java.util.concurrent.ExecutionException
                   @(grpc/invoke client {:service "protojure.http2-test"
                                         :method "BadEncoding"
                                         :output {:f example/pb->Money :ch output}}))))))

(deftest grpc-route-creation-test
  (testing "Check that protoc generated fact is accurately converted to route(s)")
  (let [routes (pedestal.routes/->tablesyntax {:rpc-metadata greeter/rpc-metadata})]
    (clojure.pprint/pprint routes)
    (is (= (map first routes)
           (seq ["/example.hello.Greeter/SayHello"
                 "/example.hello.Greeter/SayRepeatHello"
                 "/example.hello.Greeter/SayHelloAfterDelay"
                 "/example.hello.Greeter/SayHelloOnDemand"
                 "/example.hello.Greeter/SayHelloError"
                 "/example.hello.Greeter/SayNil"])))))

(deftest test-grpc-flowcontrol
  (testing "Check that a round-trip GRPC request works"
    (let [output (async/chan 1)
          client @(grpc.http2/connect {:uri (str "http://localhost:" (:port @test-env)) :input-buffer-size 128})
          count 1024]
      (test.client/FlowControl client {:count count :payload-size 1024} output)
      (Thread/sleep 5000)
      (is (= count (async/<!! (clojure.core.async/reduce (fn [c _] (inc c)) 0 output)))))))

(deftest test-client-closedetect
  (testing "Check that a streaming server can detect a client disconnect"
    (let [output (async/chan 1)
          client @(grpc.http2/connect {:uri (str "http://localhost:" (:port @test-env)) :input-buffer-size 128})
          input (str (uuid/v4))]
      (-> (test.client/ClientCloseDetect client {:id input} output)
          (p/catch (fn [ex])))
      (Thread/sleep 1000)
      (grpc/disconnect client)
      (is (-> (<!! closedetect-ch) (= input))))))

(deftest test-server-closedetect
  (testing "Check that server streaming severs if the server dies"
    (let [output (async/chan 1)
          client @(grpc.http2/connect {:uri (str "http://localhost:" (:port @test-env))})
          r (test.client/ServerCloseDetect client {} output)]
      (async/thread
        (Thread/sleep 1000)
        (swap! test-env update :server pedestal/stop))
      (is (thrown? java.util.concurrent.ExecutionException @r)))))

(deftest client-idle-timeout
  (testing "Check that idle-timeout properly sets client timeout"
    (let [input (async/chan 1)
          output (async/chan 16)
          client @(grpc.http2/connect {:uri               (str "http://localhost:" (:port @test-env))
                                       :idle-timeout      1
                                       :input-buffer-size 128})]
      (Thread/sleep 2000)
      (is (thrown? java.util.concurrent.ExecutionException
                   @(test.client/Async client {:id input}))))))

(deftest test-grpc-metadata
  (testing "Check that connection-metadata is sent to the server"
    (let [client @(grpc.http2/connect {:uri (str "http://localhost:" (:port @test-env)) :metadata {"authorization" "Magic"}})]
      (is (-> @(test.client/Metadata client {}) :msg (= "Hello, Magic")))
      (grpc/disconnect client))))

(deftest test-grpc-exception
  (let [client @(grpc.http2/connect {:uri (str "http://localhost:" (:port @test-env))})]
    (testing "Check that exceptions thrown on the server propagate back to the client"
      (is (thrown? java.util.concurrent.ExecutionException
                   @(test.client/ShouldThrow client {})))
      (try
        @(test.client/ShouldThrow client {})
        (assert false)                                      ;; we should never get here
        (catch java.util.concurrent.ExecutionException e
          (let [{:keys [status]} (ex-data (.getCause e))]
            (is (= status 13))))))
    (testing "Check that we can still connect even after exceptions have been received"
      (is (-> @(test.client/Async client {}) :msg (= "Hello, Async"))))
    (grpc/disconnect client)))

(deftest test-grpc-async
  (testing "Check that async processing functions correctly"
    (let [client @(grpc.http2/connect {:uri (str "http://localhost:" (:port @test-env))})]
      (is (-> @(test.client/Async client {}) :msg (= "Hello, Async")))
      (grpc/disconnect client))))

(deftest test-grpc-empty
  (testing "Check that empty parameters are passed correctly"
    (let [client @(grpc.http2/connect {:uri (str "http://localhost:" (:port @test-env))})]
      @(test.client/AllEmpty client {})
      (grpc/disconnect client))))

(deftest test-grpc-async-empty
  (testing "Check that an empty result-set is handled "
    (let [output (async/chan 1)
          client @(grpc.http2/connect {:uri (str "http://localhost:" (:port @test-env))})]
      @(test.client/AsyncEmpty client {} output)
      (grpc/disconnect client))))

(deftest test-client-transmit-dataframe-exception
  (testing "Check that a client transmit exception propagates correctly"
    (let [output (async/chan 1)
          input (async/chan 1)
          client @(grpc.http2/connect {:uri (str "http://localhost:" (:port @test-env))})
          p  (grpc/invoke client {:service "foobar"
                                  :method  "NotExist"
                                  :input   {:f example/new-Money :ch input}
                                  :output  {:f example/pb->Money :ch output}})]
      (async/>!! input {:currency_code (apply str (repeat 20 "foobar")) :units 42 :nanos 750000000})
      (is (thrown-with-msg? java.util.concurrent.ExecutionException #"bad status response" @p)))))

(deftest test-grpc-denied-streamer
  (testing "Check that a streaming GRPC that encounters a permission denied terminates properly"
    (let [output (async/chan 1)
          client @(grpc.http2/connect {:uri (str "http://localhost:" (:port @test-env))})]
      (is (thrown? java.util.concurrent.ExecutionException
                   @(test.client/DeniedStreamer client {} output)))
      (grpc/disconnect client))))

(deftest test-grpc-error
  (let [client @(grpc.http2/connect {:uri (str "http://localhost:" (:port @test-env))})]
    (testing "Check that grpc-status/message works properly for unary"
      (check-throw 16 @(test.client/ReturnError client {:status 16 :message "Unary Oops"})))
    (testing "Check that grpc-status/message works properly for streaming"
      (check-throw 16 @(test.client/ReturnErrorStreaming client {:status 16 :message "Streaming Oops"} (async/chan 1))))))

(defn test-bandwidth
  [mode]
  (let [client @(grpc.http2/connect {:uri (str "http://localhost:" (:port @test-env))})]
    (time @(test.client/BandwidthTest client (cond-> {:mode mode}
                                               (contains? upload-types mode) (assoc :data bandwidth-payload))))))

(deftest test-upload-bandwidth
  (testing "Check upload bandwidth of e2e"
    (test-bandwidth :mode-upload)))

(deftest test-download-bandwidth
  (testing "Check download bandwidth of e2e"
    (-> (test-bandwidth :mode-download)
        :data
        count
        (str " bytes")
        (println))))

(deftest test-bidi-bandwidth
  (testing "Check bidi bandwidth of e2e"
    (test-bandwidth :mode-bidi)))

