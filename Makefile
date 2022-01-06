# Copyright © 2019 State Street Bank and Trust Company.  All rights reserved
#
# SPDX-License-Identifier: Apache-2.0

NAME = protojure
LEIN = $(shell which lein || echo ./lein)
VERSION = $(shell cat project.clj | grep defproject | awk '{ print $$3 }' | sed 's/\"//g')
OUTPUT = target/$(NAME)-$(VERSION).jar
POM = target/pom.xml
DOC = target/doc/index.html

COVERAGE_THRESHOLD = 82
COVERAGE_EXCLUSION += "user"
COVERAGE_EXCLUSION += "protojure.internal.io"

DEPS = Makefile project.clj $(shell find src -type f)

all: scan test bin doc

scan:
	$(LEIN) cljfmt check

# 'deep-scan' is a target for useful linters that are not conducive to automated checking,
# typically because they present some false positives without an easy mechanism to overrule
# them.  So we provide the target to make it easy to run by hand, but leave them out of the
# automated gates.
deep-scan: scan
	-$(LEIN) bikeshed
	-$(LEIN) kibit

.PHONY: test
test:
	$(LEIN) cloverage --fail-threshold $(COVERAGE_THRESHOLD) $(patsubst %,-e %, $(COVERAGE_EXCLUSION))

doc: $(DOC)

$(DOC): $(DEPS)
	$(LEIN) codox

bin: $(OUTPUT) $(POM)

$(POM): $(DEPS)
	$(LEIN) pom
	cp pom.xml $@

$(OUTPUT): $(DEPS)
	$(LEIN) jar

clean:
	$(LEIN) clean

.PHONY: protos
protos:
	protoc --clojure_out=grpc-client,grpc-server:test --proto_path=test/resources $(shell find test/resources -name "*.proto" | sed 's|test/resources/||g')
