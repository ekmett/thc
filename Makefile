# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

CABAL ?= cabal
GHC ?= ghc
GHC_PKG ?=
RUN_GHC ?= runghc
GRADLE_FLAGS ?=
CABAL_FLAGS ?=
CORE_PACKAGES ?= ghc-internal base
PANDOC ?= pandoc
DOCS_REVISION ?= $(shell git rev-parse HEAD)
DOCS_CABAL_FLAGS = $(CABAL_FLAGS) --with-compiler='$(GHC)' --builddir=build/docs/cabal -j2
export GRADLE_USER_HOME ?= $(CURDIR)/.gradle-user-home
export JAVA_HOME

CORE_PREFLIGHT = GHC='$(GHC)' GHC_PKG='$(GHC_PKG)' $(RUN_GHC) -f "$$(command -v '$(GHC)')" --ghc-arg=-package --ghc-arg=ghc --ghc-arg=-package --ghc-arg=Cabal scripts/check-ghc-core.hs

.PHONY: all runtime haskell run jar fixtures test jit-test probe clean distclean check-java check-ghc-core
.PHONY: docs docs-haskell docs-jvm docs-check check-pandoc

all: runtime haskell

runtime: check-java
	./gradlew installDist $(GRADLE_FLAGS)

haskell:
	@set -e; pkg=$$($(CORE_PREFLIGHT) advisory $(CORE_PACKAGES)); \
	  $(CABAL) build $(CABAL_FLAGS) --with-compiler='$(GHC)' --with-hc-pkg="$$pkg"

run: all
	@set -e; pkg=$$($(CORE_PREFLIGHT) advisory $(CORE_PACKAGES)); \
	  $(CABAL) run thc $(CABAL_FLAGS) --with-compiler='$(GHC)' --with-hc-pkg="$$pkg" -- $(ARGS)

check-ghc-core:
	@$(CORE_PREFLIGHT) check $(CORE_PACKAGES)

# These deliberately have no fixture, test, native library, or installDist dependency.
# Run sequentially even when the caller uses make -j; both compilers are bounded.
docs: check-pandoc
	$(MAKE) docs-haskell
	$(MAKE) docs-jvm
	$(CABAL) run exe:thc-docs $(DOCS_CABAL_FLAGS) -- build '$(DOCS_REVISION)' '$(PANDOC)'

docs-haskell:
	$(CABAL) haddock lib:thc $(DOCS_CABAL_FLAGS) --haddock-html --haddock-quickjump \
	  --haddock-output-dir='$(CURDIR)/build/docs/haskell' \
	  --haddock-html-location='https://hackage.haskell.org/package/$$pkg-$$version/docs' \
	  --haddock-option='--theme=$(CURDIR)/docs/site/haddock.css' \
	  --haddock-option=--built-in-themes \
	  --haddock-option='--source-base=https://github.com/ekmett/thc/tree/$(DOCS_REVISION)/compiler' \
	  --haddock-option='--source-module=https://github.com/ekmett/thc/blob/$(DOCS_REVISION)/compiler/%{MODULE/.//}.hs' \
	  --haddock-option='--source-entity=https://github.com/ekmett/thc/blob/$(DOCS_REVISION)/compiler/%{MODULE/.//}.hs#L%L'
	@printf '%s\n' '$(DOCS_REVISION)' > build/docs/haskell.revision

docs-jvm: check-java
	./gradlew dokkaGeneratePublicationHtml $(GRADLE_FLAGS) --max-workers=2 -Pthc.docsRevision='$(DOCS_REVISION)'
	@printf '%s\n' '$(DOCS_REVISION)' > build/docs/jvm.revision

docs-check:
	$(CABAL) run exe:thc-docs $(DOCS_CABAL_FLAGS) -- check '$(DOCS_REVISION)'

check-pandoc:
	@command -v '$(PANDOC)' >/dev/null || { printf '%s\n' 'Install Pandoc 3.x (or set PANDOC) to render the documentation guides.' >&2; exit 1; }

jar: check-java
	./gradlew jar $(GRADLE_FLAGS)

fixtures: check-java
	scripts/prepare-tests.sh

test: fixtures
	./gradlew test $(GRADLE_FLAGS) $(if $(TESTS),--tests '$(TESTS)')

jit-test: fixtures
	./gradlew jitStabilityTest $(GRADLE_FLAGS)

probe: check-java
	./gradlew probe $(GRADLE_FLAGS) --args='$(ARGS)'

clean:
	$(RM) -r -- build dist dist-newstyle dist-thc

distclean: clean
	$(RM) -r -- .gradle .kotlin .gradle-user-home

check-java:
	@test -n "$${JAVA_HOME:-}" && test -x "$$JAVA_HOME/bin/java" && test -x "$$JAVA_HOME/bin/javac" || { \
		printf '%s\n' 'Set JAVA_HOME to a GraalVM 25.3.4.1 installation (JDK 25), with bin/java and bin/javac.' \
		  'For example: export JAVA_HOME=/path/to/graalvm-jdk-25' >&2; \
		exit 1; \
	}
