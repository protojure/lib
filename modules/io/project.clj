(defproject io.github.protojure/io "2.0.3-SNAPSHOT"
  :description "IO library to support io.github.protojure/core"
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
                 [org.clojure/core.async]]
  :aot [protojure.internal.io])
