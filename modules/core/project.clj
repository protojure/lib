(defproject io.github.protojure/core "2.6.1-SNAPSHOT"
  :description "Core protobuf and GRPC utilities for protojure"
  :url "http://github.com/protojure/lib"
  :license {:name "Apache License 2.0"
            :url "https://www.apache.org/licenses/LICENSE-2.0"
            :year 2022
            :key "apache-2.0"}
  :repositories {"snapshots" {:url "https://pkgs.dev.azure.com/patch-opz/Patch/_packaging/patch-mirror/maven/v1"
                              :username "patch-opz"
                              :password [:gpg :env/azure_access_token]}
                 "releases" {:url "https://pkgs.dev.azure.com/patch-opz/Patch/_packaging/patch-mirror/maven/v1"
                             :username "patch-opz"
                             :password [:gpg :env/azure_access_token]}}

  :deploy-repositories {"releases" {:url "https://pkgs.dev.azure.com/patch-opz/Patch/_packaging/patch-mirror/maven/v1"
                                    :username "patch-opz"
                                    :password [:gpg :env/azure_access_token]
                                    :sign-releases false}
                        "snapshots" {:url "https://pkgs.dev.azure.com/patch-opz/Patch/_packaging/patch-mirror/maven/v1"
                                     :username "patch-opz"
                                     :password [:gpg :env/azure_access_token]}}
  :plugins [[lein-cljfmt "0.9.0"]
            [lein-kibit "0.1.8"]
            [lein-bikeshed "0.5.2"]
            [lein-set-version "0.4.1"]
            [lein-parent "0.3.9"]]
  :parent-project {:path "../../project.clj"
                   :inherit [:managed-dependencies :javac-options]}
  :dependencies [[org.clojure/clojure]
                 [org.clojure/core.async]
                 [org.clojure/tools.logging]
                 [io.github.protojure/io]
                 [com.google.protobuf/protobuf-java]
                 [org.apache.commons/commons-compress]
                 [commons-io/commons-io]
                 [funcool/promesa]])
