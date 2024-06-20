(defproject io.github.protojure/test "2.0.7-SNAPSHOT"
  :description "Test harness for protojure libs"
  :url "http://github.com/protojure/lib"
  :license {:name "Apache License 2.0"
            :url "https://www.apache.org/licenses/LICENSE-2.0"
            :year 2022
            :key "apache-2.0"}
  :plugins [[lein-cljfmt "0.9.2"]
            [lein-set-version "0.4.1"]
            [lein-cloverage "1.2.4"]
            [lein-parent "0.3.9"]]
  :parent-project {:path "../project.clj"
                   :inherit [:managed-dependencies :javac-options]}
  :profiles {:dev {:dependencies   [[org.clojure/clojure]
                                    [org.clojure/core.async]
                                    [protojure/google.protobuf]
                                    [com.google.protobuf/protobuf-java]
                                    [org.apache.commons/commons-compress]
                                    [commons-io/commons-io]
                                    [funcool/promesa]
                                    [javax.servlet/javax.servlet-api]
                                    [io.undertow/undertow-core]
                                    [io.undertow/undertow-servlet]
                                    [io.pedestal/pedestal.log]
                                    [io.pedestal/pedestal.service]
                                    [org.eclipse.jetty.http2/http2-client]
                                    [org.eclipse.jetty/jetty-alpn-java-client]
                                    [lambdaisland/uri]
                                    [org.clojure/tools.logging]
                                    [org.clojure/tools.namespace "1.4.4"]
                                    [clj-http "3.12.3"]
                                    [com.taoensso/timbre "6.2.2"]
                                    [com.fzakaria/slf4j-timbre "0.4.0"]
                                    [org.slf4j/jul-to-slf4j "2.0.9"]
                                    [org.slf4j/jcl-over-slf4j "2.0.9"]
                                    [org.slf4j/log4j-over-slf4j "2.0.9"]
                                    [org.clojure/data.codec "0.1.1"]
                                    [org.clojure/data.generators "1.0.0"]
                                    [danlentz/clj-uuid "0.1.9"]
                                    [eftest "0.6.0"]
                                    [criterium "0.4.6"]
                                    [circleci/bond "0.6.0"]
                                    [crypto-random "1.2.1"]]
                   :resource-paths ["test/resources"]}}
  :source-paths ["../modules/io/src" "../modules/core/src" "../modules/grpc-client/src" "../modules/grpc-server/src"]
  :java-source-paths ["../modules/io/src"]
  :cloverage {:runner :eftest
              :runner-opts {:multithread? false
                            :fail-fast? true}
              :fail-threshold 81})
