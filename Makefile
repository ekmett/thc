# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

CABAL ?= cabal
GRADLE_FLAGS ?=
CABAL_FLAGS ?=
export GRADLE_USER_HOME ?= $(CURDIR)/.gradle-user-home
export JAVA_HOME

.PHONY: all runtime haskell run jar fixtures test jit-test probe clean distclean check-java

all: runtime haskell

runtime: check-java
	./gradlew installDist $(GRADLE_FLAGS)

haskell:
	$(CABAL) build $(CABAL_FLAGS)

run: all
	$(CABAL) run thc $(CABAL_FLAGS) -- $(ARGS)

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
