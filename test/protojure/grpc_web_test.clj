;; Copyright © 2019 State Street Bank and Trust Company.  All rights reserved
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
            [clojure.data.codec.base64 :as b64]
            [protojure.pedestal.interceptors.grpc :as grpc]
            [clojure.core.async :as async]
            [example.hello.Greeter :as greeter]
            [clojure.core.async :refer [<!! >!! <! >! go go-loop] :as async]
            [protojure.test.utils :as test.utils]
            [protojure.grpc.client.api :as grpc-api]
            [protojure.grpc.client.providers.http2 :as grpc.http2]
            [protojure.grpc.client.utils :as client.utils]
            [promesa.core :as p]
            [protojure.internal.grpc.client.providers.http2.jetty :as jetty-client]
            [protojure.grpc.status :as grpc.status]
            [protojure.pedestal.routes :as pedestal.routes]
            [example.hello :refer [new-HelloRequest pb->HelloReply]]
            [clj-http.client :as client]
            [protojure.grpc.codec.lpm :as lpm]
            [protojure.test.grpc.TestService.server :as test.server]
            [protojure.pedestal.interceptors.grpc-web :as grpc.web]
            [taoensso.timbre.appenders.core :as appenders]
            [taoensso.timbre :as log]
            [taoensso.timbre.tools.logging :refer [use-timbre]]))

(use-timbre)

(log/set-config! {:level        :trace
                  :ns-whitelist ["protojure.*"]
                  :appenders    {:println (appenders/println-appender {:stream :auto})}})

(defonce test-env (atom {}))

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

(defn- greeter-mock-routes [interceptors]
  (pedestal.routes/->tablesyntax {:rpc-metadata     greeter/rpc-metadata
                                  :interceptors     interceptors
                                  :callback-context (Greeter.)}))

;;-----------------------------------------------------------------------------
;; TestService service endpoint
;;-----------------------------------------------------------------------------

(deftype TestService []
  test.server/Service
  (Metadata
    [_ request]
    (throw (Exception. "foobar"))))

(defn- test-service-mock-routes [interceptors]
  (pedestal.routes/->tablesyntax {:rpc-metadata test.server/rpc-metadata
                                  :interceptors interceptors
                                  :callback-context (TestService.)}))

(defn- grpc-connect
  ([] (grpc-connect (:port @test-env)))
  ([port]
   @(grpc.http2/connect {:uri (str "http://localhost:" port) :content-coding "gzip"})))

;;-----------------------------------------------------------------------------
;; Fixtures
;;-----------------------------------------------------------------------------
(defn create-service []
  (let [port (test.utils/get-free-port)
        interceptors [(body-params/body-params)
                      pedestal/html-body]
        server-params {:env                      :prod
                       ::pedestal/routes         (into #{} (concat
                                                            (greeter-mock-routes interceptors)
                                                            (test-service-mock-routes interceptors)))
                       ::pedestal/port           port

                       ::pedestal/type           protojure.pedestal/config
                       ::pedestal/chain-provider protojure.pedestal/provider}
        client-params {:port port}]

    (let [server (test.utils/start-pedestal-server server-params)
          client @(jetty-client/connect client-params)
          grpc-client (grpc-connect port)]
      (swap! test-env assoc :port port :server server :client client :grpc-client grpc-client))))

(defn destroy-service []
  (swap! test-env update :grpc-client grpc-api/disconnect)
  (swap! test-env update :client jetty-client/disconnect)
  (swap! test-env update :server pedestal/stop))

(defn wrap-service [test-fn]
  (create-service)
  (test-fn)
  (destroy-service))

(use-fixtures :once wrap-service)

(deftest grpc-web-test-check
  (let [in (async/chan 10)
        out (async/chan 10)
        resp-in (async/chan 10)
        resp-out (async/chan 10)]
    (lpm/encode new-HelloRequest in out {:encoding identity})
    (lpm/decode pb->HelloReply resp-in resp-out {:encoding identity})
    (async/>!! in {:name "World"})
    (async/close! in)
    (testing "Check that a round-trip unary grpc-web-text request works"
      (let [lpm (async/<!! (async/into [] out))
            b64-encoded (-> (java.util.Base64/getEncoder)
                            (.encode (byte-array lpm)))
            body (-> (client/post
                      (str "http://localhost:" (:port @test-env) "/example.hello.Greeter/SayHello")
                      {:body         b64-encoded
                       :content-type "application/grpc-web-text"
                       :accept       "application/grpc-web-text"})
                     :body)
            decoded-body (.decode (java.util.Base64/getDecoder) body)]
        (doseq [b (into [] decoded-body)]
          (async/>!! resp-in b))
        (async/close! resp-in)
        (is (= "Hello, World" (:message (async/<!! resp-out))))))))

(deftest grpc-web-exception-check
  (testing "Check that wff grpc-web trailers are received when a handler throws an exception"
    (let [resp (client/post
                (str "http://localhost:" (:port @test-env) "/protojure.test.grpc.TestService/Metadata")
                {:content-type "application/grpc-web-text"
                 :accept       "application/grpc-web-text"})]
      (is (= "gAAAAHxncnBjLXN0YXR1czoxMw0KZ3JwYy1tZXNzYWdlOmphdmEubGFuZy5FeGNlcHRpb24gaW4gSW50ZXJjZXB0b3IgOnByb3RvanVyZS50ZXN0LmdycGMuVGVzdFNlcnZpY2UvTWV0YWRhdGEtaGFuZGxlciAtIGZvb2Jhcg0K"
             (:body resp))))))

(deftest grpc-web-bad-request-encoding-check
  (testing "Check that wff grpc-web trailers are received when a handler throws an exception"
    (let [resp (client/post
                (str "http://localhost:" (:port @test-env) "/protojure.test.grpc.TestService/Metadata")
                {:content-type "application/grpc-web-text"
                 :accept       "application/grpc-web-text"
                 :body (byte-array [1])})]
      (is (= "gAAAADhncnBjLXN0YXR1czozDQpncnBjLW1lc3NhZ2U6QmFkIEJhc2U2NCBFbmNvZGVkIFJlcXVlc3QNCg=="
             (:body resp))))))

(deftest grpc-web-bad-request-encoding-check
  (testing "Check that wff grpc-web trailers are received when a handler throws an exception"
    (let [resp (client/post
                (str "http://localhost:" (:port @test-env) "/protojure.test.grpc.TestService/Metadata")
                {:content-type "application/grpc-web"
                 :accept       "application/grpc-web"
                 :body (byte-array [1])})]
      (is (clojure.string/includes?
           (:body resp) "grpc-status:13\r")))))