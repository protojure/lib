(def protojure-version "2.0.1-SNAPSHOT")

(defproject io.github.protojure/lib-suite "0.0.1"
  :description "Support libraries for protoc-gen-clojure, providing native Clojure support for Google Protocol Buffers and GRPC applications"
  :url "http://github.com/protojure/lib"
  :license {:name "Apache License 2.0"
            :url "https://www.apache.org/licenses/LICENSE-2.0"
            :year 2022
            :key "apache-2.0"}
  :plugins [[lein-set-version "0.4.1"]
            [lein-sub "0.3.0"]]
  :managed-dependencies [[org.clojure/clojure "1.10.3"]
                         [org.clojure/core.async "1.5.648"]
                         [org.clojure/tools.logging "1.2.3"]
                         [io.github.protojure/io ~protojure-version]
                         [io.github.protojure/core ~protojure-version]
                         [io.github.protojure/grpc-client ~protojure-version]
                         [io.github.protojure/grpc-server ~protojure-version]
                         [protojure/google.protobuf "1.0.0"]]
  :javac-options ["-target" "11" "-source" "11"]
  :sub ["modules/io" "modules/core" "modules/grpc-client" "modules/grpc-server" "modules/test"])
