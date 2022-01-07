(defproject io.github.protojure/grpc-server "2.0.1-SNAPSHOT"
  :description "GRPC server library for protoc-gen-clojure"
  :url "http://github.com/protojure/lib"
  :license {:name "Apache License 2.0"
            :url "https://www.apache.org/licenses/LICENSE-2.0"
            :year 2022
            :key "apache-2.0"}
  :plugins [[lein-cljfmt "0.8.0"]
            [lein-kibit "0.1.8"]
            [lein-bikeshed "0.5.2"]
            [lein-set-version "0.4.1"]
            [lein-parent "0.3.8"]]
  :parent-project {:path "../../project.clj"
                   :inherit [:managed-dependencies :javac-options]}
  :dependencies [[org.clojure/clojure]
                 [org.clojure/core.async]
                 [io.github.protojure/core]
                 [io.undertow/undertow-core "2.2.14.Final"]
                 [io.undertow/undertow-servlet "2.2.14.Final"]
                 [io.pedestal/pedestal.log "0.5.9"]
                 [io.pedestal/pedestal.service "0.5.9"]])
