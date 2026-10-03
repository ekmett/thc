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
HLINT ?= hlint
HLINT_FLAGS ?=
DOCS_REVISION ?= $(shell git rev-parse HEAD)
DOCS_CABAL_FLAGS = $(CABAL_FLAGS) --with-compiler='$(GHC)' --builddir=build/docs/cabal -j2
export GRADLE_USER_HOME ?= $(CURDIR)/.gradle-user-home
export JAVA_HOME

CORE_PREFLIGHT = GHC='$(GHC)' GHC_PKG='$(GHC_PKG)' $(RUN_GHC) -f "$$(command -v '$(GHC)')" --ghc-arg=-package --ghc-arg=ghc --ghc-arg=-package --ghc-arg=Cabal bin/check-ghc-core.hs

.PHONY: all runtime haskell run jar fixtures test test-modes probe clean distclean check-java check-ghc-core
.PHONY: docs docs-haskell docs-jvm docs-check check-pandoc
.PHONY: lint-haskell
.PHONY: foreign-exception-fixtures foreign-exception-test-modes

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

# HLint reads .hlint.yaml; the runner selects tracked Haskell sources.
# Hints retain HLint's nonzero exit status. No compiler or JVM build is needed.
lint-haskell:
	HLINT='$(HLINT)' bash bin/lint-haskell.sh $(HLINT_FLAGS)

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
	  --haddock-option=--built-in-themes \
	  --haddock-option='--theme=$(CURDIR)/docs/site/haddock.css' \
	  --haddock-option='--source-base=https://github.com/ekmett/thc/tree/$(DOCS_REVISION)/src/compiler' \
	  --haddock-option='--source-module=https://github.com/ekmett/thc/blob/$(DOCS_REVISION)/src/compiler/%{MODULE/.//}.hs' \
	  --haddock-option='--source-entity=https://github.com/ekmett/thc/blob/$(DOCS_REVISION)/src/compiler/%{MODULE/.//}.hs#L%L'
	@printf '%s\n' '$(DOCS_REVISION)' > build/docs/haskell.revision
	$(CABAL) haddock lib:runtime $(DOCS_CABAL_FLAGS) --haddock-html --haddock-quickjump \
	  --haddock-output-dir='$(CURDIR)/build/docs/runtime' \
	  --haddock-html-location='https://hackage.haskell.org/package/$$pkg-$$version/docs' \
	  --haddock-option=--built-in-themes \
	  --haddock-option='--theme=$(CURDIR)/docs/site/haddock.css' \
	  --haddock-option='--source-base=https://github.com/ekmett/thc/tree/$(DOCS_REVISION)/src/runtime' \
	  --haddock-option='--source-module=https://github.com/ekmett/thc/blob/$(DOCS_REVISION)/src/runtime/%{MODULE/.//}.hs' \
	  --haddock-option='--source-entity=https://github.com/ekmett/thc/blob/$(DOCS_REVISION)/src/runtime/%{MODULE/.//}.hs#L%L'
	@printf '%s\n' '$(DOCS_REVISION)' > build/docs/runtime.revision

docs-jvm: check-java
	./gradlew javadoc $(GRADLE_FLAGS) --max-workers=2 -Pthc.docsRevision='$(DOCS_REVISION)'
	@printf '%s\n' '$(DOCS_REVISION)' > build/docs/jvm.revision

docs-check:
	$(CABAL) run exe:thc-docs $(DOCS_CABAL_FLAGS) -- check '$(DOCS_REVISION)'

check-pandoc:
	@command -v '$(PANDOC)' >/dev/null || { printf '%s\n' 'Install Pandoc 3.x (or set PANDOC) to render the documentation guides.' >&2; exit 1; }

jar: check-java
	./gradlew jar $(GRADLE_FLAGS)

fixtures: check-java
ifneq ($(strip $(TESTS)),)
	GHC='$(GHC)' GHC_PKG='$(GHC_PKG)' CABAL='$(CABAL)' python3 .github/scripts/fast_fixtures.py --tests '$(TESTS)'
else
	bin/prepare-tests.sh
endif

test: fixtures
	./gradlew test $(GRADLE_FLAGS) $(if $(TESTS),--tests '$(TESTS)')

test-modes: fixtures
	./gradlew $(GRADLE_FLAGS) --continue testDefault $(if $(TESTS),--tests '$(TESTS)') testDense $(if $(TESTS),--tests '$(TESTS)')

# This lane needs complete installed Core plus matching configured GHC sources.
# Missing prerequisites are errors; run it alongside the portable test targets.
foreign-exception-fixtures:
	@printf '%s\n' 'Foreign-exception preparation is quarantined: see docs/fixture-quarantine.log.' >&2
	@exit 2

foreign-exception-test-modes: foreign-exception-fixtures
	./gradlew $(GRADLE_FLAGS) --continue foreignExceptionTest foreignExceptionDenseTest

probe: check-java
	./gradlew probe $(GRADLE_FLAGS) --args='$(ARGS)'

clean:
	$(RM) -r -- build dist dist-newstyle dist-thc

distclean: clean
	$(RM) -r -- .gradle .gradle-user-home

check-java:
	@test -n "$${JAVA_HOME:-}" && test -x "$$JAVA_HOME/bin/java" && test -x "$$JAVA_HOME/bin/javac" || { \
		printf '%s\n' 'Set JAVA_HOME to a GraalVM 25.3.4.1 installation (JDK 25), with bin/java and bin/javac.' \
		  'For example: export JAVA_HOME=/path/to/graalvm-jdk-25' >&2; \
		exit 1; \
	}
