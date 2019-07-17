;; Copyright © 2019 State Street Bank and Trust Company.  All rights reserved
;;
;; SPDX-License-Identifier: Apache-2.0

(ns protojure.pedestal.ssl
  (:require [clojure.java.io :as io])
  (:import (javax.net.ssl SSLContext KeyManagerFactory)
           (java.security KeyStore)))

(defn- load-keystore
  [keystore password]
  (if (instance? KeyStore keystore)
    keystore
    (with-open [in (io/input-stream keystore)]
      (doto (KeyStore/getInstance (KeyStore/getDefaultType))
        (.load in (.toCharArray password))))))

(defn- keystore->key-managers
  "Return a KeyManager[] given a KeyStore and password"
  [keystore password]
  (.getKeyManagers
   (doto (KeyManagerFactory/getInstance (KeyManagerFactory/getDefaultAlgorithm))
     (.init keystore (.toCharArray password)))))

(defn keystore->
  "Turn a keystore, which may be either strings denoting file paths or actual KeyStore
  instances, into an SSLContext instance"
  [{:keys [keystore key-password]}]
  (let [ks (load-keystore keystore key-password)]
    (doto (SSLContext/getInstance "TLS")
      (.init (keystore->key-managers ks key-password) nil nil))))
