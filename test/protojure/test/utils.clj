;; Copyright © 2019 State Street Bank and Trust Company.  All rights reserved
;;
;; SPDX-License-Identifier: Apache-2.0

(ns protojure.test.utils
  (:require [clojure.data :as data]
            [io.pedestal.http :as pedestal]))

(defn get-free-port []
  (let [socket (java.net.ServerSocket. 0)]
    (.close socket)
    (.getLocalPort socket)))

(defn data-equal?
  "Returns true if the data items have no differences according to clojure.data/diff"
  [a b]
  (let [[a-diff b-diff _] (data/diff a b)]
    (and (nil? a-diff) (nil? b-diff))))

(defn start-pedestal-server [desc]
  (let [server (pedestal/create-server desc)]
    (pedestal/start server)
    server))
