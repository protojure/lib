(defproject io.github.protojure/test "2.0.1-SNAPSHOT"
  :description "Test harness for protojure libs"
  :url "http://github.com/protojure/lib"
  :license {:name "Apache License 2.0"
            :url "https://www.apache.org/licenses/LICENSE-2.0"
            :year 2022
            :key "apache-2.0"}
  :plugins [[lein-set-version "0.4.1"]
            [lein-sub "0.3.0"]
            [lein-eftest "0.5.9"]
            [lein-parent "0.3.8"]]
  :parent-project {:path "../../project.clj"
                   :inherit [:managed-dependencies :javac-options]}
  :profiles {:dev {:dependencies [[io.github.protojure/grpc-client]
                                  [io.github.protojure/grpc-server]
                                  [protojure/google.protobuf]
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
  :eftest {:multithread? false})
