;;;----------------------------------------------------------------------------------
;;; Generated by protoc-gen-clojure.  DO NOT EDIT
;;;
;;; GRPC protojure.test.grpc.TestService Service Implementation
;;;----------------------------------------------------------------------------------
(ns protojure.test.grpc.TestService.server
  (:require [protojure.test.grpc :refer :all]
            [com.google.protobuf :as com.google.protobuf]))

;-----------------------------------------------------------------------------
; GRPC TestService
;-----------------------------------------------------------------------------
(defprotocol Service
  (BandwidthTest [this param])
  (BidirectionalStreamTest [this param])
  (ClientCloseDetect [this param])
  (FlowControl [this param])
  (ReturnError [this param])
  (AllEmpty [this param])
  (ServerCloseDetect [this param])
  (Async [this param])
  (DeniedStreamer [this param])
  (AsyncEmpty [this param])
  (Metadata [this param])
  (ReturnErrorStreaming [this param])
  (ShouldThrow [this param]))

(def TestService-service-name "protojure.test.grpc.TestService")

(defn- BandwidthTest-dispatch
  [ctx request]
  (BandwidthTest ctx request))
(defn- BidirectionalStreamTest-dispatch
  [ctx request]
  (BidirectionalStreamTest ctx request))
(defn- ClientCloseDetect-dispatch
  [ctx request]
  (ClientCloseDetect ctx request))
(defn- FlowControl-dispatch
  [ctx request]
  (FlowControl ctx request))
(defn- ReturnError-dispatch
  [ctx request]
  (ReturnError ctx request))
(defn- AllEmpty-dispatch
  [ctx request]
  (AllEmpty ctx request))
(defn- ServerCloseDetect-dispatch
  [ctx request]
  (ServerCloseDetect ctx request))
(defn- Async-dispatch
  [ctx request]
  (Async ctx request))
(defn- DeniedStreamer-dispatch
  [ctx request]
  (DeniedStreamer ctx request))
(defn- AsyncEmpty-dispatch
  [ctx request]
  (AsyncEmpty ctx request))
(defn- Metadata-dispatch
  [ctx request]
  (Metadata ctx request))
(defn- ReturnErrorStreaming-dispatch
  [ctx request]
  (ReturnErrorStreaming ctx request))
(defn- ShouldThrow-dispatch
  [ctx request]
  (ShouldThrow ctx request))

(def ^:const rpc-metadata
  [{:pkg "protojure.test.grpc" :service "TestService" :method "BandwidthTest" :method-fn BandwidthTest-dispatch :server-streaming false :client-streaming false :input pb->BigPayload :output new-BigPayload}
   {:pkg "protojure.test.grpc" :service "TestService" :method "BidirectionalStreamTest" :method-fn BidirectionalStreamTest-dispatch :server-streaming true :client-streaming true :input pb->SimpleRequest :output new-SimpleResponse}
   {:pkg "protojure.test.grpc" :service "TestService" :method "ClientCloseDetect" :method-fn ClientCloseDetect-dispatch :server-streaming true :client-streaming false :input pb->CloseDetectRequest :output com.google.protobuf/new-Any}
   {:pkg "protojure.test.grpc" :service "TestService" :method "FlowControl" :method-fn FlowControl-dispatch :server-streaming true :client-streaming false :input pb->FlowControlRequest :output new-FlowControlPayload}
   {:pkg "protojure.test.grpc" :service "TestService" :method "ReturnError" :method-fn ReturnError-dispatch :server-streaming false :client-streaming false :input pb->ErrorRequest :output com.google.protobuf/new-Empty}
   {:pkg "protojure.test.grpc" :service "TestService" :method "AllEmpty" :method-fn AllEmpty-dispatch :server-streaming false :client-streaming false :input com.google.protobuf/pb->Empty :output com.google.protobuf/new-Empty}
   {:pkg "protojure.test.grpc" :service "TestService" :method "ServerCloseDetect" :method-fn ServerCloseDetect-dispatch :server-streaming true :client-streaming false :input com.google.protobuf/pb->Empty :output com.google.protobuf/new-Any}
   {:pkg "protojure.test.grpc" :service "TestService" :method "Async" :method-fn Async-dispatch :server-streaming false :client-streaming false :input com.google.protobuf/pb->Empty :output new-SimpleResponse}
   {:pkg "protojure.test.grpc" :service "TestService" :method "DeniedStreamer" :method-fn DeniedStreamer-dispatch :server-streaming true :client-streaming false :input com.google.protobuf/pb->Empty :output com.google.protobuf/new-Empty}
   {:pkg "protojure.test.grpc" :service "TestService" :method "AsyncEmpty" :method-fn AsyncEmpty-dispatch :server-streaming true :client-streaming false :input com.google.protobuf/pb->Empty :output com.google.protobuf/new-Empty}
   {:pkg "protojure.test.grpc" :service "TestService" :method "Metadata" :method-fn Metadata-dispatch :server-streaming false :client-streaming false :input com.google.protobuf/pb->Empty :output new-SimpleResponse}
   {:pkg "protojure.test.grpc" :service "TestService" :method "ReturnErrorStreaming" :method-fn ReturnErrorStreaming-dispatch :server-streaming true :client-streaming false :input pb->ErrorRequest :output com.google.protobuf/new-Empty}
   {:pkg "protojure.test.grpc" :service "TestService" :method "ShouldThrow" :method-fn ShouldThrow-dispatch :server-streaming false :client-streaming false :input com.google.protobuf/pb->Empty :output com.google.protobuf/new-Empty}])
