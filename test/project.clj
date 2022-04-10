(defproject io.github.protojure/test "2.0.2-SNAPSHOT"
  :description "Test harness for protojure libs"
  :url "http://github.com/protojure/lib"
  :license {:name "Apache License 2.0"
            :url "https://www.apache.org/licenses/LICENSE-2.0"
            :year 2022
            :key "apache-2.0"}
  :plugins [[lein-cljfmt "0.8.0"]
            [lein-set-version "0.4.1"]
            [lein-cloverage "1.2.2"]
            [lein-parent "0.3.8"]]
  :parent-project {:path "../project.clj"
                   :inherit [:managed-dependencies :javac-options]}
  :profiles {:dev {:dependencies [[org.clojure/clojure]
                                  [org.clojure/core.async]
                                  [protojure/google.protobuf]
                                  [com.google.protobuf/protobuf-java]
                                  [org.apache.commons/commons-compress]
                                  [commons-io/commons-io]
                                  [funcool/promesa]
                                  [io.undertow/undertow-core]
                                  [io.undertow/undertow-servlet]
                                  [io.pedestal/pedestal.log]
                                  [io.pedestal/pedestal.service]
                                  [org.eclipse.jetty.http2/http2-client]
                                  [org.eclipse.jetty/jetty-alpn-java-client]
                                  [lambdaisland/uri]
                                  [org.clojure/tools.logging]
                                  [org.apache.logging.log4j/log4j-core "2.17.1"]
                                  [org.apache.logging.log4j/log4j-slf4j-impl "2.17.1"]
                                  [org.clojure/tools.namespace "1.2.0"]
                                  [clj-http "3.12.3"]
                                  [com.taoensso/timbre "5.1.2"]
                                  [org.clojure/data.codec "0.1.1"]
                                  [org.clojure/data.generators "1.0.0"]
                                  [danlentz/clj-uuid "0.1.9"]
                                  [eftest "0.5.9"]
                                  [criterium "0.4.6"]
                                  [crypto-random "1.2.1"]]
                   :resource-paths ["test/resources"]}}
  :source-paths ["../modules/io/src" "../modules/core/src" "../modules/grpc-client/src" "../modules/grpc-server/src"]
  :java-source-paths ["../modules/io/src"]
  :cloverage {:runner :eftest
              :runner-opts {:multithread? false
                            :fail-fast? true}
              :fail-threshold 81})
