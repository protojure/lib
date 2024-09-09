(def protojure-version "2.11.0")

(defproject io.github.protojure/lib-suite "0.0.1"
  :description "Support libraries for protoc-gen-clojure, providing native Clojure support for Google Protocol Buffers and GRPC applications"
  :url "http://github.com/protojure/lib"
  :license {:name "Apache License 2.0"
            :url "https://www.apache.org/licenses/LICENSE-2.0"
            :year 2022
            :key "apache-2.0"}
  :plugins [[lein-set-version "0.4.1"]
            [lein-sub "0.3.0"]]
  :managed-dependencies [[org.clojure/clojure "1.12.0"]
                         [org.clojure/core.async "1.6.681"]
                         [org.clojure/tools.logging "1.3.0"]
                         [com.google.protobuf/protobuf-java "4.28.0"]
                         [org.apache.commons/commons-compress "1.27.1"]
                         [commons-io/commons-io "2.16.1"]
                         [funcool/promesa "9.2.542"]
                         [javax.servlet/javax.servlet-api "4.0.1"]
                         [io.undertow/undertow-core "2.3.17.Final"]
                         [io.undertow/undertow-servlet "2.3.17.Final"]
                         [io.pedestal/pedestal.log "0.7.0"]
                         [io.pedestal/pedestal.service "0.7.0"]
                         [io.pedestal/pedestal.error "0.7.0"]
                         [org.eclipse.jetty.http2/http2-client "11.0.24"]
                         [org.eclipse.jetty/jetty-alpn-java-client "12.0.13"]
                         [lambdaisland/uri "1.19.155"]
                         [io.github.protojure/io ~protojure-version]
                         [io.github.protojure/core ~protojure-version]
                         [io.github.protojure/grpc-client ~protojure-version]
                         [io.github.protojure/grpc-server ~protojure-version]
                         [protojure/google.protobuf "1.0.0"]]
  :javac-options ["-target" "11" "-source" "11"]
  :sub ["modules/io" "modules/core" "modules/grpc-client" "modules/grpc-server"])
