<!-- SPDX-FileCopyrightText: 2026 Edward Kmett -->
<!-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause -->

# GHC API probes

Three small programs exercise progressively larger parts of the pinned GHC
9.14.1 library: `ghc-faststring` interns an input string, `ghc-session`
initializes a compiler session, and `ghc-load` loads/typechecks `subjects/Probe.hs`
without generating or linking native object code.

Native controls (from this directory):

```sh
cabal build all
cabal run ghc-faststring -- "THC λ" "GHC API"
cabal run ghc-session -- "$(ghc --print-libdir)"
cabal run ghc-load -- "$(ghc --print-libdir)" subjects/Probe.hs
```

All three native controls passed on 2026-09-26. Expected output is respectively
`THC λ GHC API` followed by `True`, `GHC session ready; verbosity=0`, and
`GHC module load/typecheck succeeded`.

For THC, use the project-directory driver path, complete installed Core, and
the matching configured GHC source tree:

```sh
thc run examples/standard-apps/ghc-api --exe ghc-faststring \
  --installed-core required --ghc-source /path/to/ghc-9.14.1 \
  --thc-root /path/to/thc --dist-dir /path/to/thc/build/ghc-api/guest-faststring \
  -- "THC λ" "GHC API"
```

The Haskell fixture runner retains native output, the ordinary THC run, strict
audit and provenance together. From the repository root, select the pinned GHC
9.14.1 installation with complete Core for `ghc` and its dependencies, its
matching configured source tree, and GraalVM 25.3.4.1 / JDK 25. Build the runtime
with `./gradlew installDist`; see the [full-Core build guide](../../../docs/ghc-core.md).

```sh
export THC_INSTALLED_CORE_GHC_SOURCE=/path/to/ghc-9.14.1
cabal run exe:thc-fixtures -- ghc-api faststring
# Compiler sessions install process handlers; standalone Linux launches must
# explicitly relinquish the JVM's INT/QUIT/HUP/TERM handlers:
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:+$JAVA_TOOL_OPTIONS }-Xrs"
cabal run exe:thc-fixtures -- ghc-api session load
```

`THC_TEST_DRIVER` may select an already-built production driver and
`THC_TEST_RUNTIME` an installed runtime launcher. This permits runtime-only
iteration without unnecessarily changing acquisition-tool identities. A failed
probe retains its command logs under `build/ghc-api/guest-PROBE/logs` and does
not publish a success manifest. Successful probes require a strict audit and
byte-for-byte native stdout equality. The default runs all three probes in order.

On 2026-09-26, `ghc-faststring` passed the complete ordinary THC workflow on
Linux x86_64 with the bytecode runtime: all 822 compiler interfaces acquired,
strict audit accepted, original compiler Core executed, stdout matched native
byte for byte, and normal Handle shutdown completed. The accepted graph had
370,612 supplied bindings, 2,098 reachable bindings, no missing globals, and no
issues. The retained success receipt is `build/ghc-api/guest-faststring/manifest.json`.

```text
THC λ GHC API
True
```

The preceding attempt exposed twelve record-selector identity collisions;
preserving GHC's constructor-qualified field names resolved them. No compiler
bodies were substituted and strict admission was not weakened. Preserve the
installed-Core cache when resuming: acquisition is expensive, but unchanged
compiler bundles can be reused by these probes and other compiler-library apps.

This demonstrates FastString interning through the real compiler library, not
general GHC API or GHCi/native object-loader support. The subsequent `runGhc`
session probe acquired successfully, then failed strict admission before guest
execution: 370,616 supplied bindings, 26,818 reachable bindings, 99 unresolved
foreign identifiers and 186 issues. The unresolved identifiers are secondary to
unimplemented foreign calls, not 99 missing Haskell modules. The frontier includes
additional compiler-global CAF hooks, event/process calls, unsupported literals,
and aggregate boundaries; the retained report is `build/ghc-api/guest-session/audit.json`.
Module load/typecheck has not yet run in THC. Compiler RTS hook tests are
separate lower-level coverage. `runGhc` temporarily installs process signal
handlers; merely importing the `ghc` package does not. See the
[standalone signal policy](../../../docs/process-signals.md) before embedding.
