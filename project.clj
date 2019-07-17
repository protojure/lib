(defproject protojure "0.4.0-alpha12-SNAPSHOT"
  :description "Support library for protoc-gen-clojure, providing native Clojure support for Google Protocol Buffers and GRPC applications"
  :url "http://github.com/protojure/library"
  :license {:name "Apache License 2.0"
            :url "https://www.apache.org/licenses/LICENSE-2.0"
            :year 2019
            :key "apache-2.0"}
  :plugins [[lein-codox "0.10.4"]
            [lein-cljfmt "0.5.7"]
            [jonase/eastwood "0.2.6"]
            [lein-kibit "0.1.6"]
            [lein-bikeshed "0.5.1"]
            [lein-cloverage "1.0.13"]]
  :dependencies [[org.clojure/clojure "1.10.0" :scope "provided"]
                 [org.clojure/core.async "0.4.490" :scope "provided"]
                 [com.google.protobuf/protobuf-java "3.7.1" :scope "provided"]
                 [io.undertow/undertow-core "2.0.20.Final" :scope "provided"]
                 [io.undertow/undertow-servlet "2.0.20.Final" :scope "provided"]
                 [org.eclipse.jetty.http2/http2-client "9.4.17.v20190418" :scope "provided"]
                 [io.pedestal/pedestal.log "0.5.5" :scope "provided"]
                 [io.pedestal/pedestal.service "0.5.5" :scope "provided"]
                 [org.clojure/tools.logging "0.4.1"]
                 [org.apache.commons/commons-compress "1.18"]
                 [commons-io/commons-io "2.6"]
                 [funcool/promesa "2.0.1"]
                 [lambdaisland/uri "1.1.0"]]
  :aot [protojure.internal.grpc.codec.io
        protojure.internal.pedestal.io]
  :codox {:metadata {:doc/format :markdown}
          :namespaces [#"^(?!protojure.internal)"]}
  :eastwood {:debug [:none]
             :exclude-linters [:constant-test]
             :add-linters [:unused-namespaces]
             :config-files [".eastwood-overrides"]
             :exclude-namespaces [example.hello protojure.grpc-test]}
  :profiles {:dev {:dependencies [[org.clojure/tools.namespace "0.2.11"]
                                  [clj-http "3.9.1"]
                                  [com.taoensso/timbre "4.10.0"]
                                  [org.clojure/data.codec "0.1.1"]]
                   :resource-paths ["test/resources"]}})

