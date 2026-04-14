(def protojure-version "2.12.0-SNAPSHOT")

(defproject io.github.protojure/lib-suite "0.0.1"
  :description "Support libraries for protoc-gen-clojure, providing native Clojure support for Google Protocol Buffers and GRPC applications"
  :url "http://github.com/protojure/lib"
  :license {:name "Apache License 2.0"
            :url "https://www.apache.org/licenses/LICENSE-2.0"
            :year 2022
            :key "apache-2.0"}
  :plugins [[lein-set-version "0.4.1"]
            [lein-sub "0.3.0"]]
  :managed-dependencies [[org.clojure/clojure "1.12.4"]
                         [org.clojure/core.async "1.9.865"]
                         [org.clojure/tools.logging "1.3.1"]
                         [com.google.protobuf/protobuf-java "4.34.1"]
                         [org.apache.commons/commons-compress "1.28.0"]
                         [commons-io/commons-io "2.21.0"]
                         [funcool/promesa "11.0.678"]
                         [javax.servlet/javax.servlet-api "4.0.1"]
                         [io.undertow/undertow-core "2.3.24.Final"]
                         [io.undertow/undertow-servlet "2.3.24.Final"]
                         [io.pedestal/pedestal.log "0.7.2"]
                         [io.pedestal/pedestal.service "0.7.2"]
                         [io.pedestal/pedestal.error "0.7.2"]
                         [org.eclipse.jetty.http2/http2-client "11.0.26"]
                         [org.eclipse.jetty/jetty-alpn-java-client "12.1.8"]
                         [lambdaisland/uri "1.19.155"]
                         [io.github.protojure/io ~protojure-version]
                         [io.github.protojure/core ~protojure-version]
                         [io.github.protojure/grpc-client ~protojure-version]
                         [io.github.protojure/grpc-server ~protojure-version]
                         [protojure/google.protobuf "1.0.0"]]
  :javac-options ["-target" "11" "-source" "11"]
  :sub ["modules/io" "modules/core" "modules/grpc-client" "modules/grpc-server"])
