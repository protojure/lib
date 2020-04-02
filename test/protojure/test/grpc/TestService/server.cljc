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
  (CloseDetect [this param])
  (FlowControl [this param])
  (Metadata [this param])
  (ShouldThrow [this param])
  (Async [this param]))

(defn- CloseDetect-dispatch
  [ctx request]
  (CloseDetect ctx request))
(defn- FlowControl-dispatch
  [ctx request]
  (FlowControl ctx request))
(defn- Metadata-dispatch
  [ctx request]
  (Metadata ctx request))
(defn- ShouldThrow-dispatch
  [ctx request]
  (ShouldThrow ctx request))
(defn- Async-dispatch
  [ctx request]
  (Async ctx request))

(def ^:const rpc-metadata
  [{:pkg "protojure.test.grpc" :service "TestService" :method "CloseDetect" :method-fn CloseDetect-dispatch :server-streaming true :client-streaming false :input pb->CloseDetectRequest :output com.google.protobuf/new-Any}
   {:pkg "protojure.test.grpc" :service "TestService" :method "FlowControl" :method-fn FlowControl-dispatch :server-streaming true :client-streaming false :input pb->FlowControlRequest :output new-FlowControlPayload}
   {:pkg "protojure.test.grpc" :service "TestService" :method "Metadata" :method-fn Metadata-dispatch :server-streaming false :client-streaming false :input com.google.protobuf/pb->Empty :output new-SimpleResponse}
   {:pkg "protojure.test.grpc" :service "TestService" :method "ShouldThrow" :method-fn ShouldThrow-dispatch :server-streaming false :client-streaming false :input com.google.protobuf/pb->Empty :output com.google.protobuf/new-Empty}
   {:pkg "protojure.test.grpc" :service "TestService" :method "Async" :method-fn Async-dispatch :server-streaming false :client-streaming false :input com.google.protobuf/pb->Empty :output new-SimpleResponse}])
