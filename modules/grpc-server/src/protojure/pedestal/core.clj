;; Copyright © 2019 State Street Bank and Trust Company.  All rights reserved
;; Copyright © 2019-2022 Manetu, Inc.  All rights reserved
;;
;; SPDX-License-Identifier: Apache-2.0

(ns protojure.pedestal.core
  "A [Pedestal](http://pedestal.io/) [chain provider](http://pedestal.io/reference/chain-providers) compatible with simultaneously serving both [GRPC-HTTP2](https://github.com/grpc/grpc/blob/master/doc/PROTOCOL-HTTP2.md) and [GRPC-WEB](https://github.com/grpc/grpc/blob/master/doc/PROTOCOL-WEB.md) protocols"
  (:require [io.pedestal.http :as http]
            [io.pedestal.interceptor.chain :as pedestal.chain]
            [io.pedestal.interceptor.helpers :as pedestal.interceptors]
            [io.pedestal.log :as log]
            [clojure.string :as string]
            [clojure.pprint :refer [pprint]]
            [clojure.core.async :refer [go-loop <!! <! go chan >!! >! close! timeout poll! promise-chan]]
            [promesa.core :as p]
            [clojure.java.io :as io]
            [protojure.pedestal.ssl :as ssl]
            [protojure.internal.io :as pio])
  (:import (io.undertow.server HttpHandler
                               HttpServerExchange
                               ServerConnection
                               ServerConnection$CloseListener)
           (io.undertow Undertow
                        UndertowOptions)
           (io.undertow.server.protocol.http HttpAttachments)
           (io.undertow.util HeaderMap
                             HttpString)
           (java.io InputStream)
           (java.net InetSocketAddress InetAddress)
           (io.undertow.io Receiver$PartialBytesCallback Receiver$ErrorCallback)
           (java.nio ByteBuffer)
           (org.xnio.channels StreamSinkChannel)
           (java.util.concurrent Executors ThreadPoolExecutor)
           (clojure.lang IPersistentCollection IFn)
           (java.time LocalTime))
  (:refer-clojure :exclude [resolve flush]))

(set! *warn-on-reflection* true)

(defn- assoc-header
  "Associates an undertow header entry as a <string, string> tuple in a map"
  [^HeaderMap headers map ^HttpString key]
  (assoc map (string/lower-case key) (.get headers key 0)))

(defn- get-request-headers
  "Create a map of request header elements"
  [^HttpServerExchange exchange]
  (let [headers (.getRequestHeaders exchange)]
    (reduce (partial assoc-header headers) {} (.getHeaderNames headers))))

(defn- get-ssl-client-cert
  "Defensively retrivies SSL client certificate from exchange."
  [^HttpServerExchange exchange]
  (try
    (some-> exchange (.getConnection) (.getSslSessionInfo) (.getPeerCertificateChain))
    (catch Exception _
      nil)))

(defn- write-trailers
  "Places the <string, string> tuples representing trailers to undertow with a putAttachment operation"
  [^HttpServerExchange exchange trailers]
  (let [data (HeaderMap.)]
    (doseq [[k v] trailers]
      (.add data (HttpString. (name k)) (str v)))
    (.putAttachment exchange HttpAttachments/RESPONSE_TRAILERS data)))

(defn- read-stream-chunk
  "Chunks reads from an input-stream, returning either a byte-array when data is found, or nil for EOF"
  [^InputStream stream requested-size]
  (let [buf (byte-array requested-size)
        actual-size (.read stream buf)]
    (cond
      (= actual-size -1) nil                                      ;; EOF
      (< actual-size requested-size) (byte-array actual-size buf) ;; resize if smaller
      :else buf)))

(defn- byte-chunk-seq
  "Returns a lazy sequence of byte-array chunks of length 'size' from an input stream"
  [stream size]
  (lazy-seq (when-let [buf (read-stream-chunk stream size)]
              (cons buf (byte-chunk-seq stream size)))))

(defn- async-poll-seq
  "Returns a lazy sequence of (non-blocking) items available on a core.async channel"
  [ch]
  (lazy-seq (when-some [data (poll! ch)]
              (cons data (async-poll-seq ch)))))

(defn- -write
  [^StreamSinkChannel ch ^ByteBuffer buf len]
  (let [bytes-written (.write ch buf)
        bytes-remain (- len bytes-written)]
    (when-not (zero? bytes-remain)
      (.awaitWritable ch)
      (-write ch buf bytes-remain))))

(defmulti write-data
  "Writes the provided bytes to the undertow response channel"
  (fn [ch data] (type data)))
(defmethod write-data (Class/forName "[B")
  [^StreamSinkChannel ch data]
  (-write ch (ByteBuffer/wrap data) (count data)))
(defmethod write-data ByteBuffer
  [^StreamSinkChannel ch ^ByteBuffer data]
  (-write ch data (.remaining data)))

(defn- soft-flush
  "Soft-flush the channel, ignoring any 'false' return status.  We will hard-flush before shutting down the connection"
  [^StreamSinkChannel ch]
  (.flush ch))

(defn- write-data-coll
  "Writes and flushes an entire collection"
  [^StreamSinkChannel ch coll]
  (run! (partial write-data ch) coll)
  (soft-flush ch))

(defn- write-direct-data
  "Used for trivial response bodies, such as String or byte types"
  [^StreamSinkChannel ch data]
  (p/create
   (fn [resolve reject]
     (resolve (do
                (write-data ch data)
                (soft-flush ch))))))

(defn- write-streaming-data
  "Used for InputStream type response bodies.  Will chunk the data to avoid
  overburdening the heap"
  [^StreamSinkChannel ch is]
  (p/create
   (fn [resolve reject]
     (resolve (write-data-coll ch (byte-chunk-seq is 65536))))))

(defn- write-available-async-data
  "Drains all remaining data from a core.async channel and flushes it to the response channel"
  [^StreamSinkChannel output-ch input-ch]
  (write-data-coll output-ch (async-poll-seq input-ch)))

(defn- write-async-data
  "Used for core.async type response bodies.  Each message received is assumed
  to represent a data frame and thus will be flushed"
  [^StreamSinkChannel output-ch input-ch]
  (p/create
   (fn [resolve reject]
     (write-available-async-data output-ch input-ch)
     (try
       (loop []
         (if-let [data (<!! input-ch)]
           (do
             (write-data output-ch data)
             (write-available-async-data output-ch input-ch)
             (recur))
           (resolve true)))
       (catch Exception e
         (reject e))))))

(defn- open-output-channel
  "Opens the response channel and sets it up for asynchronous operation"
  [^HttpServerExchange exchange]
  (log/debug "opening output channel" exchange)
  (let [output-ch (.getResponseChannel exchange)]
    (.resumeWrites output-ch)
    output-ch))

(defn- close-output-channel
  "Flushes and closes the response channel"
  [^HttpServerExchange exchange ^StreamSinkChannel ch]
  (log/debug "closing output channel" exchange)
  (try
    (.shutdownWrites ch)
    (let [deadline (.plusSeconds (LocalTime/now) 30)]
      (loop []
        (if (.flush ch)                                       ;; hard-flush in a loop until the channel is drained
          (.close ch)
          (if (.isBefore (LocalTime/now) deadline)
            (do
              (Thread/sleep 10)
              (recur))
            (throw (ex-info "failed to flush output-channel" {}))))))
    (catch Exception e
      (log/error :msg "close-output-channel"
                 :exception e)))
  (log/debug "channel closed" exchange))

(defn- open-input-channel
  "Receives request body as a callback and puts the bytes on a core.async channel"
  [exchange ch]
  (let [receiver (.getRequestReceiver ^HttpServerExchange exchange)]
    (p/create
     (fn [resolve reject]
       (.receivePartialBytes receiver
                             (reify Receiver$PartialBytesCallback
                               (handle [this exchange message last]
                                 (.pause receiver)
                                 (when-not (empty? message)
                                   (>!! ch (ByteBuffer/wrap message)))
                                 (when last
                                   (close! ch)
                                   (resolve true))
                                 (.resume receiver)))
                             (reify Receiver$ErrorCallback
                               (error [this exchange e]
                                 (close! ch)
                                 (reject e))))))))

(defmulti  ^:no-doc transmit-body
  "Handle transmitting the response body based on the type"
  (fn [ch resp-body] (type resp-body)))
(defmethod transmit-body clojure.core.async.impl.channels.ManyToManyChannel
  [ch resp-body]
  (write-async-data ch resp-body))
(defmethod transmit-body String
  [ch ^String resp-body]
  (write-direct-data ch (.getBytes resp-body)))
(defmethod transmit-body InputStream
  [ch resp-body]
  (write-streaming-data ch resp-body))
(defmethod transmit-body java.io.File
  [ch resp-body]
  (with-open [is (io/input-stream resp-body)]
    (write-streaming-data ch is)))
(defmethod transmit-body (Class/forName "[B")
  [ch resp-body]
  (write-direct-data ch resp-body))
(defmethod transmit-body nil
  [ch resp-body]
  (p/resolved true))
(defmethod transmit-body IPersistentCollection
  [ch resp-body]
  (transmit-body ch (with-out-str (pr resp-body))))
(defmethod transmit-body IFn
  [ch resp-body]
  (let [os (doto (java.io.ByteArrayOutputStream.)
             (resp-body))
        out (String. (.toByteArray os))]
    (transmit-body ch out)))
(defmethod transmit-body :default
  [ch resp-body]
  (transmit-body ch (with-out-str (pr resp-body))))
(prefer-method transmit-body IPersistentCollection IFn)

(defmulti ^:no-doc transmit-trailers
  "Handle transmitting the trailers based on the type"
  (fn [exchange trailers] (type trailers)))
(defmethod transmit-trailers clojure.core.async.impl.channels.ManyToManyChannel
  [exchange trailers]
  (p/create
   (fn [resolve reject]
     (go
       (resolve (write-trailers exchange (<! trailers)))))))
(defmethod transmit-trailers nil
  [exchange trailers]
  (p/resolved true))
(defmethod transmit-trailers :default
  [exchange trailers]
  (p/create
   (fn [resolve reject]
     (resolve (write-trailers exchange trailers)))))

(defn disconnect! [channel]
  (>!! channel true))

(defn handle-disconnect [connections k]
  (swap! connections
         (fn [x]
           (let [channels (some-> x (get k) (vals))]
             (run! disconnect! channels))
           (dissoc x k))))

(defn add-close-listener [^ServerConnection conn f]
  (.addCloseListener conn f))

(defn conn-id [^ServerConnection conn]
  (-> conn .getPeerAddress hash))

(defn subscribe-close [connections ^HttpServerExchange exchange]
  (let [conn (.getConnection exchange)
        kc (conn-id conn)
        ke (hash exchange)
        ch (promise-chan)]
    (swap! connections
           (fn [x]
             (update x kc
                     (fn [y]
                       (when (nil? y)
                         (add-close-listener conn
                                             (reify ServerConnection$CloseListener
                                               (closed [_ _]
                                                 (handle-disconnect connections kc)))))
                       (assoc y ke ch)))))
    ch))

(defn unsubscribe-close [connections ^HttpServerExchange exchange]
  (let [conn (.getConnection exchange)
        kc (conn-id conn)
        ke (hash exchange)]
    (swap! connections
           (fn [x]
             (update x kc (fn [y]
                            (when-let [channel (get y ke)]
                              (disconnect! channel))
                            (dissoc y ke)))))))

(declare handle-response)

(defn chain-execute [request interceptors]
  (p/create
   (fn [resolve reject]
     (let [response-handler (pedestal.interceptors/on-response ::container resolve)]
       (pedestal.chain/execute {:request request} (cons response-handler interceptors))))))

(defn- make-request-map [input-ch connections ^HttpServerExchange exchange]
  (let [input-stream (pio/new-inputstream {:ch input-ch})
        headers      (get-request-headers exchange)
        ssl-cert     (get-ssl-client-cert exchange)]
    (cond-> {:query-string     (.getQueryString exchange)
             :request-method   (keyword (string/lower-case (.toString (.getRequestMethod exchange))))
             :headers          headers
             :body             input-stream
             :body-ch          input-ch
             :close-ch         (subscribe-close connections exchange)
             :uri              (.getRequestURI exchange)
             :path-info        (.getRequestPath exchange)
             :protocol         (.toString (.getProtocol exchange))
             :remote-addr      (some-> exchange
                                       (.getConnection)
                                       ^InetSocketAddress (.getPeerAddress InetSocketAddress)
                                       ^InetAddress (.getAddress)
                                       (.getHostAddress))
             :server-name      (.getHostName exchange)
             :server-port      (.getHostPort exchange)
             :scheme           (keyword (.getRequestScheme exchange))
             :async-supported? true}
      (contains? headers "content-type")
      (assoc :content-type (get headers "content-type"))

      (not (neg? (.getRequestContentLength exchange)))
      (assoc :content-length (.getRequestContentLength exchange))

      (some? (.getRequestCharset exchange))
      (assoc :character-encoding (.getRequestCharset exchange))

      (seq ssl-cert)
      (assoc :ssl-client-cert ssl-cert))))

(defn- handle-request
  "Our main handler - Every request arriving at the undertow endpoint
  filters through this function.  We decode it and then invoke our pedestal
  chain asynchronously"
  [^ThreadPoolExecutor pool interceptors connections ^HttpServerExchange exchange]
  (let [input-ch         (chan 16384) ;; TODO this allocation likely needs adaptive tuning
        input-status     (open-input-channel exchange input-ch)
        request          (make-request-map input-ch connections exchange)]
    (.execute pool
              (fn []
                (try
                  (let [response @(chain-execute request interceptors)]
                    (handle-response connections exchange input-status response))
                  (catch Exception ex
                    (log/error :msg "unhandled" :exception ex)))))))

(defn- handle-response
  "This function is invoked when the interceptor chain has fully executed and it is now time to
  initiate the response to the client"
  [connections
   ^HttpServerExchange exchange
   input-status
   {:keys [status headers body trailers] :as response}]

  (log/debug "response:" response)

  ;; Set Response StatusCode
  (.setStatusCode exchange status)

  ;; Set Response Headers
  (let [ctx (.getResponseHeaders exchange)]
    (doseq [[^String k ^String v] headers]
      (cond
        (string? v) (.put ctx (HttpString. k) v)
        (coll? v) (.putAll ctx (HttpString. k) v))))

  ;; Start asynchronous output
  (let [output-ch (open-output-channel exchange)]
    @(-> (p/all [input-status
                 (transmit-trailers exchange trailers)
                 (transmit-body output-ch body)])
         (p/finally (fn [_ _]
                      (close-output-channel exchange output-ch)
                      (unsubscribe-close connections exchange)
                      (.endExchange exchange))))))

(defn provider
  "Generates our undertow provider, which defines the callback point between
  the undertow container and pedestal by dealing with the dispatch interop.
  Our real work occurs in the (request) form above"
  [service-map]
  (let [interceptors (::http/interceptors service-map)
        pool (Executors/newCachedThreadPool)
        connections (atom {})]
    (assoc service-map ::handler
           (reify HttpHandler
             (handleRequest [this exchange]
               (handle-request pool interceptors connections exchange))))))

(defn config
  "Given a service map (with interceptor provider established) and a server-opts map,
   Return a map of :server, :start-fn, and :stop-fn.
   Both functions are 0-arity"
  [service-map
   {:keys [host port]
    {:keys [ssl-port] :as ssl-config} :container-options
    :or   {host  "127.0.0.1"}}]
  (let [handler (::handler service-map)
        server (-> (Undertow/builder)
                   (cond->
                     ;; start http listener when no ssl-context is set
                     ;; or if ssl-port is set in addition to port
                    (or (nil? ssl-config)
                        (and port ssl-port))
                     (.addHttpListener port host)
                     ;; listens on port unless ssl-port is set
                     (some? ssl-config)
                     (.addHttpsListener (or ssl-port port) host (ssl/keystore-> ssl-config)))
                   (.setServerOption UndertowOptions/ENABLE_HTTP2 true)
                   (.setHandler handler)
                   (.build))]
    {:server   server
     :start-fn (fn []
                 (.start server)
                 server)
     :stop-fn  (fn []
                 (.stop server)
                 server)}))
