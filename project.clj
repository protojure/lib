(defproject protojure "1.2.3-SNAPSHOT"
  :description "Support library for protoc-gen-clojure, providing native Clojure support for Google Protocol Buffers and GRPC applications"
  :url "http://github.com/protojure/library"
  :license {:name "Apache License 2.0"
            :url "https://www.apache.org/licenses/LICENSE-2.0"
            :year 2020
            :key "apache-2.0"}
  :plugins [[lein-codox "0.10.4"]
            [lein-cljfmt "0.5.7"]
            [lein-kibit "0.1.6"]
            [lein-bikeshed "0.5.1"]
            [lein-cloverage "1.0.13"]]
  :dependencies [[org.clojure/clojure "1.10.1" :scope "provided"]
                 [org.clojure/core.async "0.4.500" :scope "provided"]
                 [com.google.protobuf/protobuf-java "3.9.1" :scope "provided"]
                 [io.undertow/undertow-core "2.0.26.Final" :scope "provided"]
                 [io.undertow/undertow-servlet "2.0.26.Final" :scope "provided"]
                 [org.eclipse.jetty.http2/http2-client "9.4.20.v20190813" :scope "provided"]
                 [io.pedestal/pedestal.log "0.5.7" :scope "provided"]
                 [io.pedestal/pedestal.service "0.5.7" :scope "provided"]
                 [org.clojure/tools.logging "0.5.0"]
                 [org.apache.commons/commons-compress "1.19"]
                 [commons-io/commons-io "2.6"]
                 [funcool/promesa "3.0.0"]
                 [lambdaisland/uri "1.1.0"]]
  :aot [protojure.internal.grpc.codec.io
        protojure.internal.pedestal.io]
  :codox {:metadata {:doc/format :markdown}
          :namespaces [#"^(?!protojure.internal)"]}
  :profiles {:dev {:dependencies [[protojure/google.protobuf "0.9.1"]
                                  [org.clojure/tools.namespace "0.3.1"]
                                  [clj-http "3.10.0"]
                                  [com.taoensso/timbre "4.10.0"]
                                  [org.clojure/data.codec "0.1.1"]
                                  [org.clojure/data.generators "0.1.2"]]
                   :resource-paths ["test/resources"]}})

