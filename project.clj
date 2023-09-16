(def protojure-version "2.8.1-SNAPSHOT")

(defproject io.github.protojure/lib-suite "0.0.1"
  :description "Support libraries for protoc-gen-clojure, providing native Clojure support for Google Protocol Buffers and GRPC applications"
  :url "http://github.com/protojure/lib"
  :license {:name "Apache License 2.0"
            :url "https://www.apache.org/licenses/LICENSE-2.0"
            :year 2022
            :key "apache-2.0"}
  :plugins [[lein-set-version "0.4.1"]
            [lein-sub "0.3.0"]]
  :managed-dependencies [[org.clojure/clojure "1.11.1"]
                         [org.clojure/core.async "1.6.681"]
                         [org.clojure/tools.logging "1.2.4"]
                         [com.google.protobuf/protobuf-java "3.24.3"]
                         [org.apache.commons/commons-compress "1.24.0"]
                         [commons-io/commons-io "2.13.0"]
                         [funcool/promesa "9.2.542"]
                         [javax.servlet/javax.servlet-api "4.0.1"]
                         [io.undertow/undertow-core "2.3.8.Final"]
                         [io.undertow/undertow-servlet "2.3.8.Final"]
                         [io.pedestal/pedestal.log "0.6.0"]
                         [io.pedestal/pedestal.service "0.6.0"]
                         [org.eclipse.jetty.http2/http2-client "11.0.16"]
                         [org.eclipse.jetty/jetty-alpn-java-client "12.0.1"]
                         [lambdaisland/uri "1.15.125"]
                         [io.github.protojure/io ~protojure-version]
                         [io.github.protojure/core ~protojure-version]
                         [io.github.protojure/grpc-client ~protojure-version]
                         [io.github.protojure/grpc-server ~protojure-version]
                         [protojure/google.protobuf "1.0.0"]]
  :javac-options ["-target" "11" "-source" "11"]
  :sub ["modules/io" "modules/core" "modules/grpc-client" "modules/grpc-server"])
