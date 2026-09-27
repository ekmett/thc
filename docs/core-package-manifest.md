<!-- SPDX-FileCopyrightText: 2026 Edward Kmett -->
<!-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause -->

# Core packages

When separate GHC units are linked into one THC program, each source component
must be compiled with its actual GHC unit ID. The THC plugin's `unit-qualified`
option writes each module below `units/u-<escaped-unit>/Module.json`; the legacy
flat output layout remains the default for existing single-unit fixtures.
Package modules must use `post-tidy` with GHC code generation. This is the
boundary that matches names in installed `.hi` files: a separately compiled
importer can refer to generated workers absent from the dependency's pre-Tidy
export. The package export also qualifies source file and span IDs by unit, so
two packages can use the same relative source path without merging their text.

The project planner writes one atomic manifest for the selected component
closure, using GHC unit IDs and dependencies from Cabal's plan. Its format is:

```json
{
  "format": "thc-core-packages",
  "schema": 1,
  "ghc": "9.14.1",
  "units": [
    {
      "id": "example-0.1-inplace",
      "depends": ["base-4.22.0.0-1f90"],
      "modules": [
        {
          "name": "Example",
          "boundary": "optimized-Core-after-Tidy-before-CorePrep",
          "path": "core/units/u-example-0.1-inplace/Example.json",
          "sha256": "<lowercase SHA-256 of the complete module file>"
        }
      ]
    }
  ]
}
```

Paths are relative to the manifest and cannot escape its directory. Installed
or native-only dependency units may have no Core modules, but a reachable guest
global must still have an exported definition. Both the static auditor and JVM
loader check module hashes, units, module names, boundaries and binding owners;
they reject missing reachable globals. The auditor accepts
`--package-manifest packages.json`. The JVM command-line module argument accepts
`@packages.json`, including with `--run-io`.

`scripts/test-core-package-link.py` builds an independently registered library
and importer with GHC 9.14.1, compares native output with compiled AST and
bytecode execution, and checks that removing the library fails before execution.
This focused proof uses binder/file provenance from `-g0`; it does not claim
preservation of nested expression SourceNote ticks.

## Partially resolved package-native components

An unresolved package-native artifact remains in `packageNativeArchive`, with
its complete original bytes, ABI and unresolved-symbol inventory. Older archives
without a dependency proof block the entire module. Recognizing a managed symbol
such as `memcpy` does not waive this obligation.

For LLVM bitcode, the native producer may add `entryResolution` with schema 1 and
profile `llvm-globaldce-adapter-closures-v1`. It binds the original artifact hash
to one ordered row per original ABI entry, recording that entry's LLVM closure
hash and unresolved symbols. Each closure is computed with LLVM internalization,
global dead-code elimination and verification, retaining constructor/destructor,
global and address-taken function dependencies. Entries whose closures reach an
unsupported external remain unavailable, including indirect calls through globals.

The producer then builds and verifies one union artifact for all available
entries and independently inspects its unresolved symbols. Its hash and actual
unresolved inventory are recorded in the proof. The accompanying `packageNativeLink`
keeps the original component identity, full ABI, entry indices and source recipe,
with an explicit `availableEntries` selection and the union's bytes/hash. This
single loaded component preserves shared mutable globals and one-time native
initializers; individual closures are evidence, not separately loaded libraries.
This first profile admits raw-bitcode dependencies only. A selected union that
needs an embedded native provider container (libm, entropy, width or C++ library)
remains archive-only until its container recipe can also be preserved.

Both consumers validate complete ordered coverage, component and content identity,
the exact available subset, and the final union's dependency inventory. Only the
selected ABI entries become callable. Selecting an unavailable original call
still fails before native execution. The retained original archive is never
rewritten into an assertion that its unresolved dependencies were satisfied.

The `package-native-archives` Haskell producer and
`packageNativeArchivesDefault`/`packageNativeArchivesDense` tests cover a genuine
mixed package, shared initialized C state across selected adapters, rejected
direct/global-function-pointer calls, and an unresolved constructor that prevents
admission of otherwise ordinary entries, plus a libm adapter that still needs its
native provider container. The first compiled shared-state call is
checked once after a fixed interpreted native-oracle corpus, with no retries.
