;; Copyright © 2019 State Street Bank and Trust Company.  All rights reserved
;; Copyright © 2019-2022 Manetu, Inc.  All rights reserved
;;
;; SPDX-License-Identifier: Apache-2.0

(ns protojure.protobuf.protocol)

(defprotocol Writer
  (serialize [this os]))

;; Supports 'Any' type https://developers.google.com/protocol-buffers/docs/proto3#any
(defprotocol TypeReflection
  (gettype [this]))