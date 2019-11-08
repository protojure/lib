;; Copyright © 2019 State Street Bank and Trust Company.  All rights reserved
;; Copyright © 2019 Manetu, Inc.  All rights reserved
;;
;; SPDX-License-Identifier: Apache-2.0

(ns protojure.protobuf.protocol)

(defprotocol Writer
  (serialize [this os]))