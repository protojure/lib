# Copyright © 2019 State Street Bank and Trust Company.  All rights reserved
#
# SPDX-License-Identifier: Apache-2.0

NAME = protojure
LEIN = $(shell which lein || echo ./lein)

DEPS = Makefile project.clj

all: scan test install

jetty-shaded:
	cd modules/jetty-shaded && mvn -q install

scan: jetty-shaded
	$(LEIN) sub cljfmt check
	cd test && $(LEIN) cljfmt check

# 'deep-scan' is a target for useful linters that are not conducive to automated checking,
# typically because they present some false positives without an easy mechanism to overrule
# them.  So we provide the target to make it easy to run by hand, but leave them out of the
# automated gates.
deep-scan: scan
	-$(LEIN) sub bikeshed
	-$(LEIN) sub kibit

.PHONY: test
test: jetty-shaded
	cd test && $(LEIN) cloverage

install: jetty-shaded
	$(LEIN) sub install

set-version:
	sed -i '' 's/def protojure-version \".*\"/def protojure-version \"$(VERSION)\"/' project.clj
	cd modules/jetty-shaded && mvn -q versions:set -DnewVersion=$(VERSION) -DgenerateBackupPoms=false
	$(LEIN) sub set-version $(VERSION)

clean:
	$(LEIN) sub clean
	cd test && $(LEIN) clean
	$(LEIN) clean
	cd modules/jetty-shaded && mvn -q clean

.PHONY: protos
protos:
	mkdir -p test/test/resources
	protoc --clojure_out=grpc-client,grpc-server:test/test --proto_path=test/test/resources $(shell find test/test/resources -name "*.proto" | sed 's|test/test/resources/||g')
