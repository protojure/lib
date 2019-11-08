;; Copyright © 2019 State Street Bank and Trust Company.  All rights reserved
;; Copyright © 2019 Manetu, Inc.  All rights reserved
;;
;; SPDX-License-Identifier: Apache-2.0

(ns protojure.protobuf.serdes.utils
  (:require [protojure.protobuf.serdes.stream :as stream]))

(defn tag-map
  "
  Returns a lazy sequence consisting of the result of applying f to the set of
  protobuf objects delimited by protobuf tags.

  #### Parameters

  | Value    | Type               | Description                                                                                    |
  |----------|--------------------|------------------------------------------------------------------------------------------------|
  | **init** | _map_              | A map of initial values                                                                        |
  | **f**    | _(fn [tag index])_ | An arity-2 function that accepts a tag and index and returns a [k v] (see _Return type_ below) |
  | **is**   | [CodedInputStream](https://developers.google.com/protocol-buffers/docs/reference/java/com/google/protobuf/CodedInputStream) | An input stream containing serialized protobuf data |

  #### Return Type

  _f_ should evaluate to a 2-entry vector in the form [key value], where:

  - _key_ is either
       - a keyword representing the field name when the index is known
       - simply the index value when it is not
  - _value_ is either
       - a value that will be returned verbatim to be associated to the _key_
       - a function that will take a collection of previously deserialized values with the same tag and update it to incorporate the new value (to support _repeated_ types, etc)


  #### Example

  ```
  (tag-map
      (fn [tag index]
           (case index
              1 [:currency_code (cis->String is)]
              2 [:units (cis->Int64 is)]
              3 [:nanos (cis->Int32 is)]
              [index (cis->undefined tag is)]))
      is))
  ```
  "
  ([f is]
   (tag-map {} f is))
  ([init f is]
   (loop [acc init tag (stream/read-tag is)]
     (if (pos? tag)
       (let [[k v] (f tag (bit-shift-right tag 3))]
         (recur (if (fn? v)
                  (update acc k v)
                  (assoc acc k v))
                (stream/read-tag is)))
       acc))))

(def default-scalar? #(or (nil? %) (zero? %)))
(def default-bytes? empty?)
(def default-bool? #(not (true? %)))
