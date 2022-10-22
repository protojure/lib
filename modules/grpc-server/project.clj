(defproject io.github.protojure/grpc-server "2.6.1-SNAPSHOT"
  :description "GRPC server library for protoc-gen-clojure"
  :url "http://github.com/protojure/lib"
  :license {:name "Apache License 2.0"
            :url "https://www.apache.org/licenses/LICENSE-2.0"
            :year 2022
            :key "apache-2.0"}
  :plugins [[lein-cljfmt "0.9.0"]
            [lein-kibit "0.1.8"]
            [lein-bikeshed "0.5.2"]
            [lein-set-version "0.4.1"]
            [lein-parent "0.3.9"]]
  :parent-project {:path "../../project.clj"
                   :inherit [:managed-dependencies :javac-options]}
  :dependencies [[org.clojure/clojure]
                 [org.clojure/core.async]
                 [io.github.protojure/core]
                 [io.undertow/undertow-core]
                 [io.undertow/undertow-servlet]
                 [io.pedestal/pedestal.log]
                 [io.pedestal/pedestal.service]])
