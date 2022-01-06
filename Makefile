# Copyright © 2019 State Street Bank and Trust Company.  All rights reserved
#
# SPDX-License-Identifier: Apache-2.0

NAME = protojure
LEIN = $(shell which lein || echo ./lein)

DEPS = Makefile project.clj

all: scan test install

scan:
	$(LEIN) sub cljfmt check

# 'deep-scan' is a target for useful linters that are not conducive to automated checking,
# typically because they present some false positives without an easy mechanism to overrule
# them.  So we provide the target to make it easy to run by hand, but leave them out of the
# automated gates.
deep-scan: scan
	-$(LEIN) sub bikeshed
	-$(LEIN) sub kibit

.PHONY: test
test:
	$(LEIN) sub install
	cd modules/test && $(LEIN) eftest

install:
	$(LEIN) sub install

set-version:
	sed -i '' 's/def protojure-version \".*\"/def protojure-version \"$(VERSION)\"/' project.clj
	$(LEIN) sub set-version $(VERSION)

clean:
	$(LEIN) sub clean
	$(LEIN) clean

.PHONY: protos
protos:
	protoc --clojure_out=grpc-client,grpc-server:test --proto_path=test/resources $(shell find test/resources -name "*.proto" | sed 's|test/resources/||g')
