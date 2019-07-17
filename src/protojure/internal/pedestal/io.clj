;; Copyright © 2019 State Street Bank and Trust Company.  All rights reserved
;;
;; SPDX-License-Identifier: Apache-2.0

(ns protojure.internal.pedestal.io
  (:import (protojure.internal.grpc.io InputStream)))

(gen-class
 :name protojure.pedestal.io.InputStream
 :extends javax.servlet.ServletInputStream
 :state state
 :init init
 :constructors {[Object] []}
 :exposes-methods {read parentRead})

(defn- -init [channel]
  [[] {:is (InputStream. {:ch channel})}])

(defn- -available
  [this]
  (let [{:keys [is]} (.state this)]
    (.available is)))

(defn- -read
  "Reads the next byte of data from the input stream. The value byte is returned as an int in the range 0 to 255.
  See InputStream for further details."
  ([this bytes offset len]
   (.parentRead this bytes offset len))
  ([this bytes]
   (.parentRead this bytes))
  ([this]
   (let [{:keys [is]} (.state this)]
     (.read is))))

