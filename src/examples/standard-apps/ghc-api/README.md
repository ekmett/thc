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

Expected output is respectively
`THC λ GHC API` followed by `True`, `GHC session ready; verbosity=0`, and
`GHC module load/typecheck succeeded`.

For THC, use the project-directory driver path, complete installed Core, and
the matching configured GHC source tree:

```sh
thc run ghc-faststring --project-dir src/examples/standard-apps/ghc-api \
  --installed-core required --ghc-source /path/to/ghc-9.14.1 \
  --thc-root /path/to/thc --dist-dir /path/to/thc/build/ghc-api/guest-faststring \
  -- "THC λ" "GHC API"
```

The Haskell fixture runner retains native output, the ordinary THC run and
provenance together. Strict auditing is opt-in. From the repository root, select the pinned GHC
9.14.1 installation with complete Core for `ghc` and its dependencies, its
matching configured source tree, and GraalVM 25.3.4.1 / JDK 25. Build the runtime
with `./gradlew installDist`; see the [full-Core build guide](../../../../docs/ghc-core.md).

```sh
export THC_INSTALLED_CORE_GHC_SOURCE=/path/to/ghc-9.14.1
cabal run exe:thc-fixtures -- ghc-api faststring
# Compiler sessions install process handlers; standalone Linux launches must
# explicitly relinquish the JVM's INT/QUIT/HUP/TERM handlers:
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:+$JAVA_TOOL_OPTIONS }-Xrs"
cabal run exe:thc-fixtures -- ghc-api session load
# Explicitly request the production audit and artifact verification:
cabal run exe:thc-fixtures -- ghc-api --audit faststring
```

`THC_TEST_DRIVER` may select an already-built production driver and
`THC_TEST_RUNTIME` an installed runtime launcher. This permits runtime-only
iteration without unnecessarily changing acquisition-tool identities. A failed
probe retains its command logs under `build/ghc-api/guest-PROBE/logs` and does
not publish a success manifest. Successful probes require byte-for-byte native
stdout equality. With `--audit`, a current accepting production report is required and retained.
The default runs all three probes in order. Keep the installed-Core cache between
probes; unchanged compiler bundles can be reused.

These probes do not establish general GHC API, GHCi or native object-loader
support. `runGhc` temporarily installs process signal handlers; merely importing
`ghc` does not. See the [standalone signal policy](../../../../docs/process-signals.md)
before embedding. An explicit unsupported call or missing Core definition must
be resolved for the actual selected closure before it can execute.
