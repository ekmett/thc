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

These are development probes, not a claim that GHC runs under THC. The first
THC attempt acquired thirteen installed dependency bundles through
`transformers`, but ended before compiler-library acquisition, audit or guest
execution; its termination cause is unknown. Preserve the installed-Core cache
when resuming. Compiler RTS hook tests are separate from these end-to-end runs.
`runGhc` itself temporarily installs process signal handlers; merely importing
the `ghc` package does not.
