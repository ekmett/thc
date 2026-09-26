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

The existing Haskell fixture runner can retain the native output, ordinary THC
run, strict audit and provenance together:

```sh
export THC_INSTALLED_CORE_GHC_SOURCE=/path/to/ghc-9.14.1
cabal run exe:thc-fixtures -- ghc-api faststring
# After the preceding probe succeeds:
cabal run exe:thc-fixtures -- ghc-api session load
```

`THC_TEST_DRIVER` may select an already-built production driver and
`THC_TEST_RUNTIME` an installed runtime launcher. This permits runtime-only
iteration without unnecessarily changing acquisition-tool identities. A failed
probe retains its command logs under `build/ghc-api/guest-PROBE/logs` and does
not publish a success manifest. Successful probes require a strict audit and
byte-for-byte native stdout equality. The default runs all three probes in order.

These are development probes, not a claim that GHC runs under THC. The resumed
THC attempt on 2026-09-26 acquired all dependencies and all 822 compiler-library
interfaces. Its strict audit found 370,600 supplied bindings, 2,098 reachable
bindings, no missing globals, and twelve duplicate global IDs. Those collisions
came from discarding GHC's constructor-qualified record-field namespace; the
exporter now preserves GHC's own mangled names. A fresh complete acquisition
and guest execution remain necessary before reporting end-to-end success.
Preserve the installed-Core cache when resuming. Compiler RTS hook tests are
separate from these end-to-end runs.
`runGhc` itself temporarily installs process signal handlers; merely importing
the `ghc` package does not.
