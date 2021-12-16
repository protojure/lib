(defproject protojure "1.5.16-SNAPSHOT"
  :description "Support library for protoc-gen-clojure, providing native Clojure support for Google Protocol Buffers and GRPC applications"
  :url "http://github.com/protojure/library"
  :license {:name "Apache License 2.0"
            :url "https://www.apache.org/licenses/LICENSE-2.0"
            :year 2020
            :key "apache-2.0"}
  :plugins [[lein-codox "0.10.8"]
            [lein-cljfmt "0.8.0"]
            [lein-kibit "0.1.8"]
            [lein-bikeshed "0.5.2"]
            [lein-cloverage "1.2.2"]]
  :dependencies [[org.clojure/clojure "1.10.3" :scope "provided"]
                 [org.clojure/core.async "1.5.648" :scope "provided"]
                 [com.google.protobuf/protobuf-java "3.19.1" :scope "provided"]
                 [io.undertow/undertow-core "2.2.14.Final" :scope "provided"]
                 [io.undertow/undertow-servlet "2.2.14.Final" :scope "provided"]
                 [org.eclipse.jetty.http2/http2-client "9.4.44.v20210927" :scope "provided"]
                 [org.eclipse.jetty/jetty-alpn-java-client "9.4.44.v20210927" :scope "provided"]
                 [io.pedestal/pedestal.log "0.5.9" :scope "provided"]
                 [io.pedestal/pedestal.service "0.5.9" :scope "provided"]
                 [org.clojure/tools.logging "1.2.2"]
                 [org.apache.commons/commons-compress "1.21"]
                 [commons-io/commons-io "2.11.0"]
                 [funcool/promesa "3.0.0"]
                 [lambdaisland/uri "1.12.89"]]
  :aot [protojure.internal.grpc.codec.io
        protojure.internal.pedestal.io]
  :codox {:metadata {:doc/format :markdown}
          :namespaces [#"^(?!protojure.internal)"]}
  :profiles {:dev {:dependencies [[protojure/google.protobuf "0.9.1"]
                                  [org.clojure/tools.namespace "1.2.0"]
                                  [clj-http "3.12.3"]
                                  [com.taoensso/timbre "5.1.2"]
                                  [org.clojure/data.codec "0.1.1"]
                                  [org.clojure/data.generators "1.0.0"]
                                  [danlentz/clj-uuid "0.1.9"]
                                  [eftest "0.5.9"]]
                   :resource-paths ["test/resources"]}})
