;; Copyright © 2019 State Street Bank and Trust Company.  All rights reserved
;; Copyright © 2019-2022 Manetu, Inc.  All rights reserved
;;
;; SPDX-License-Identifier: Apache-2.0

(ns user
  (:require [clojure.tools.namespace.repl :refer [refresh]]
            [eftest.runner :refer [find-tests run-tests]]))

;; to run one test: `(run-tests (find-tests #'protojure.pedestal-test/edn-check))`
;; to see output, use (run-tests ... {:capture-output? false})

(defn run-all-tests []
  (run-tests (find-tests "test") {:fail-fast? true :multithread? false}))