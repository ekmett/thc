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
global must still have an exported definition. The JVM loader checks units,
module names, boundaries and binding owners, and rejects missing reachable
globals. File-hash verification is opt-in. The separate auditor accepts
`--package-manifest packages.json`. The JVM command-line module argument accepts
`@packages.json`, including with `--run-io`. Add `--verify-artifacts` before the
guest `--` separator to check artifact hashes and complete source/index agreement.
The JVM `loadEntry` and `loadManagedExports` APIs expose `verifyArtifacts = false`.

`scripts/test-core-package-link.py` builds an independently registered library
and importer with GHC 9.14.1, compares native output with compiled AST and
bytecode execution, and checks that removing the library fails before execution.
This focused proof uses binder/file provenance from `-g0`; it does not claim
preservation of nested expression SourceNote ticks.

## Direct unit artifacts

By default, the project driver publishes a nonempty unit as two absolute artifact references:
`json: {path, sha256}` and `symbols: {path, sha256, format}`. Both must be present, and
the unit must not also select `bundle`. `core.jsons` contains original module
JSON bytes plus LF separators and small metadata projections. With
`symbols.format: "md5-utf8-u64le-v1"`, `core.symbols` contains one fixed 24-byte
record per original top-level binding: the 16 canonical MD5 digest bytes of its
exact logical qualified ID encoded as UTF-8, followed by an unsigned 64-bit
little-endian absolute JSON byte offset. Records are sorted by unsigned digest
bytes, with no header, name table or auxiliary search index. MD5 collisions are
assumed absent; lookup does not compare stored names or use collision buckets.
For example, `main:M.é😀` has digest `23415231b60de428eeaf32979e1cb8ce`.

An opt-in unit can instead select one [compact container](compact-core-format.md)
per module. This route uses the container's fixed fingerprint table and decodes
selected typed records directly, without a JSON or symbol-pair artifact.

Omitting `symbols.format` selects the compatible text directory, not binary
auto-detection. Its rows are:

```text
exact-unit:Module.binding decimal-byte-offset
```

IDs are decoded raw UTF-8, sorted by unsigned UTF-8 bytes; a row ends with LF.
The last space separates the ID from the decimal offset, so IDs can contain
spaces. Empty, duplicate, or line-breaking IDs are rejected. The offset points
to the original binding object's opening `{`, not an escaped ID or a display
name. Both formats record offsets from actual final bytes, after package-native
amendments. Directory sort order does not change Core binding order, so a
following record's offset is never a binding length. The reader maps the
directory on first lookup and decodes only the selected JSON object; neither
format requires a whole-source structural index or eager binding enumeration.

Module records retain their original name, logical path, boundary and module
hash. They add absolute byte positions, all end-exclusive: `start`/`end` select
the unchanged original module, `bindingsStart`/`bindingsEnd` include the binding
array's brackets, and `metadataStart`/`metadataEnd` select a separate object of
existing identity, constructor, ABI and foreign-admission fields. This object
excludes `bindings`, `groups` and `sourceCore`. When source tables exist,
`sourceMetadataStart`/`sourceMetadataEnd` select an object containing the original
`sourceFiles`/`sourceSpans`, independently of admission metadata. These projections
are additional bytes in the same unit payload, not replacements for original
Core or extra sidecar files.

Four Boolean module facts are derived from the original data:

- `containsDelimitedControl`: an expression contains the actual `prompt#` or
  `control0#` primitive, not merely that spelling in a string.
- `registrationObligations`: foreign files or stub initializers/finalizers exist.
- `mainAlias`: the exact `main::<Module>.main` binding exists.
- `packageScalarDeclarations`: `staticForeignImports.imports` is nonempty.

When available, the unit's `targetLayout` is the existing `thc-target-layout`
schema-1 document with the original compiler and layout records, checked against
the acquisition receipt. Missing layout is not replaced by a host assumption.
Artifact hashes remain verification metadata, not a default whole-unit scan.
The explicit auditor verifies both artifacts, exact symbol offsets, module
hashes, metadata projections and derived facts before publishing a completed
result. ZIP acquisition receipts remain in the producer cache; existing ZIP and
loose-module manifests remain valid.

## Optional JSON indexes and lazy loading

A module record may include an `index` object with exactly `path` and `sha256`:

```json
{
  "index": {
    "path": "core/units/u-example-0.1-inplace/Example.json.idx",
    "sha256": "<lowercase SHA-256 of the complete sidecar file>"
  }
}
```

The original JSON and the module record's existing fields remain unchanged. An
index describes navigation through those exact bytes; it is not another Core format.
The [sidecar producer](../compiler/json-index/README.md#sidecar-v2-producer)
documents generation and the version 2 wire layout. Records without `index`
remain valid and use the existing JSON loading path. A nearby `.idx` file is
not selected unless the manifest declares it.

For loose artifacts, the declared paths remain inside the manifest directory.
For a ZIP bundle, they name members of that bundle. JSON and index member paths
must be distinct, and the inner `manifest.json` must contain the same module
records as the outer package manifest. The ZIP inventory includes every declared
JSON and index, plus its required manifests; missing, duplicate and undeclared
members are rejected. Normal loading trusts the supplied files and sidecars,
checking their framing, extents and accessed value/ABI shapes without hashing or
rescanning the complete source. Explicit verification additionally checks artifact
and sidecar hashes, source identity, grammar and navigation metadata. Errors do
not silently fall back to the unindexed path.

Indexed loading still reads and retains an owned immutable JSON snapshot, builds
navigation over the complete topology, reads ZIP members and enumerates binding
headers eagerly. Control summaries and strict dependency discovery still visit
expressions; foreign admission may inspect additional fields. Skipping optional
verification does not make these remaining stages demand-driven.

For both backends, eligible lifted top-level functions and thunks decode body
fields and lower executable roots on demand. Entry selection prepares the entry;
a later first global read prepares its callee. Strict globals and top-level
literals, constructors and void values retain eager initialization. Preparing a
thunk does not evaluate its Haskell computation. Immutable source projections
may be shared by an Engine, while executable roots, CAFs and guest failures stay
with each Context. Runtime admission and lowering still reject unsupported code
when preparing a binding (`reject-at-binding-admission`); the separate
whole-program audit is unchanged.

Sidecars add storage alongside the unchanged JSON. Cache accounting must include
both artifacts and the ZIP's framing and compression. Index array byte counts
are not JVM heap measurements, and lazy preparation alone establishes no heap
or startup performance result.

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
