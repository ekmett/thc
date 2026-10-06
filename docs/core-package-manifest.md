<!-- SPDX-FileCopyrightText: 2026 Edward Kmett -->
<!-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause -->

# Core packages

When separate GHC units are linked into one THC program, each source component
must be compiled with its actual GHC unit ID. The THC plugin's `unit-qualified`
option writes each module below `units/u-<escaped-unit>/Module.cbd`; the flat
output layout is the default for single-unit exports.
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
          "path": "core/units/u-example-0.1-inplace/Example.cbd",
          "sha256": "<lowercase SHA-256 of the complete module file>",
          "compact": {
            "path": "/absolute/cache/unit-core/v3/<bundle-hash>/0.cbd",
            "sha256": "<same final CBD hash>",
            "format": "thc-cbd-v1"
          },
          "containsDelimitedControl": false,
          "registrationObligations": false,
          "mainAlias": false,
          "packageScalarDeclarations": false
        }
      ]
    }
  ]
}
```

Logical source paths remain relative; executable `compact.path` references are absolute. Installed
or native-only dependency units may have no Core modules, but a reachable guest
global must still have an exported definition. The JVM loader checks units,
module names, boundaries and binding owners, and rejects missing reachable
globals. CBD file-hash verification is opt-in; native interface hashes are always checked. The separate auditor accepts
`--package-manifest packages.json`. The JVM command-line module argument accepts
`@packages.json`, including with `--run-io`. Add `--verify-artifacts` before the
guest `--` separator to check artifact hashes and original source identity.
The JVM `loadEntry` and `loadManagedExports` APIs expose `verifyArtifacts = false`.

## Direct unit artifacts

The project driver publishes one [CBD container](compact-core-format.md) per
module under `unit-core/v3/<source-bundle-hash>`. Compiler exports are CBD from
the start; native linkage amends typed header facts while retaining original
executable/debug bytes. Publication copies final module bytes unchanged.
The module record retains its original name, logical path, boundary and hash,
and adds the absolute `compact` reference. No JSON extents, index, `.symbols`
sidecar or executable bundle reference is emitted. Old JSON unit records and
legacy JSON/ZIP execution inputs are rejected, not migrated or retried.

Four Boolean module facts are derived from the original data:

- `containsDelimitedControl`: an expression contains the actual `prompt#` or
  `control0#` primitive, not merely that spelling in a string.
- `registrationObligations`: foreign files or stub initializers/finalizers exist.
- `mainAlias`: the exact GHC-generated `main::Main.main` binding exists.
- `packageScalarDeclarations`: actual import/address declarations or a linked
  package-native component are present.

When available, the unit's `targetLayout` is the existing `thc-target-layout`
schema-1 document with the original compiler and layout records, checked against
the acquisition receipt. Missing layout is not replaced by a host assumption.
Artifact hashes remain verification metadata, not a default whole-unit scan.
The explicit auditor verifies module hashes and derived facts. ZIP acquisition
receipts remain in the producer cache, not as runtime execution alternatives.

## Native interface modules

The existing loading API accepts an explicit list of retained `.hi` files:

```java
var entry = Main.loadEntry(context,
    List.of("build/Example.hi", "build/Helper.hi"), "unit-id:Example.entry");
long result = entry.execute(7L).asLong();
```

Use the exact unit identity recorded by GHC, and compile these modules with
`-fwrite-if-simplified-core`. Supply each required native declaration provider;
the loader performs no directory search. Paths are canonicalized, their bytes
are content-bound to the host request, and duplicate module identities reject.
Loose inputs need no package manifest. Their declarations and binding caches
belong to the execution context; retained file snapshots are immutable. Imported
native types require native interface providers. A provider may lack retained
Core when only declarations or compiler-defined dictionary selectors are needed.
Ordinary function bodies still require `-fwrite-if-simplified-core`. CBD and native inputs may
coexist with distinct module identities.

The native JVM reader also accepts a module's `interface` artifact with
`"format": "ghc-hi"`, an absolute `path` and its raw-file `sha256`. The module's
`sha256` must match it. Omit `compact` and the helper-backed unit `interfaceSource`.
The runtime verifies the exact bytes it parses, checks module identity and
startup summaries against the interface, and feeds decoded bindings directly
to the existing linker and backends. It creates no helper process or
intermediate CBD. A declaration-only provider cannot stand in for a complete
executable module's provenance.

The reader targets GHC 9.14.1 vanilla, 64-bit interfaces. Types retain lexical
binder identity, kinds, roles, multiplicities, applications and coercions.
Capture-avoiding substitution drives polymorphic applications, constructor
instantiation, synonyms, parameterized newtypes and coercion axioms. Runtime
layouts follow the type's `RuntimeRep`; unknown representation and levity remain
unknown. Type variables erase from value arguments, while coercion and `State#`
arguments preserve their value arity with zero-width storage. Casts change type
and representation metadata without evaluating or copying their expression.

Boxed constructors use their worker fields, including GADT equality evidence,
existential binders and unpacked payloads. Layouts resolve after admission
reserves the module owner, so forward and imported declarations do not depend
on file or declaration order. Lazy fields keep their suspended values. General
newtype representation makes ordinary `IO a` work through the existing IO
execution boundary: its definition comes from the installed
`GHC.Internal.Types.hi`, rather than a loader-specific replacement.

Class declarations supply their dictionary constructor and field order. Method
and superclass selectors follow GHC's generated selector semantics, even when
that declaration provider has no retained Core. Unary classes use the same late
representation erasure as the Core exporter, without treating them as newtypes
or inferring evaluation evidence. Instance dictionaries and default methods use
their retained bodies. Pattern-synonym declarations retain matcher, builder and
record-selector identities; those functions also use their ordinary Core bodies.

Integer, byte and floating literals use the existing runtime representations.
Raw byte literals preserve embedded NUL and high bytes. Floating rationals round
directly to their IEEE format with ties to even, including subnormals and
overflow. Type-level `Symbol` literals preserve GHC's sequence of `Char` values,
including the distinction between two surrogate characters and one
supplementary character. Compiler-owned declarations and built-in coercion rules
come from the pinned generated catalogue; ordinary library declarations come
from their owning interfaces.

The focused execution fixture compares native GHC results for recursive calls,
lazy boxed fields, parameterized newtypes, GADTs, unpacking, nested tuples/sums
polymorphic IO, class methods, superclass projections and record pattern synonyms.
It runs both backends and handoff modes, including first-installed-call checks
for the pure functions and a guest-exception negative control for IO. Process
creation is disabled and the runtime receives only interfaces.

Annotation transport, remaining expression forms, native foreign artifact
admission and complete offline
execution auditing still need integration; `--verify-artifacts` rejects native
modules. The project driver does not yet publish this native format. Remaining work is tracked in
[#1063](https://github.com/ekmett/thc/issues/1063).

## Helper-backed retained interface sources

`--installed-core demand` may mix ordinary `compact` CBD unit records with
eligible whole-unit `interfaceSource` records. The latter have format
`thc-ghc-interfaces-v1`, exact `registeredUnit`, absolute selected `helper` and
`libdir`, `implicitGlobalDatabase`, `way` (`vanilla` or `dynamic`), ordered absolute `packageDatabases`, and
recorded `compiler`. Their module rows replace `compact` and logical `path`
with `interface: {"path": "/absolute/Module.hi", "sha256": "<raw input hash>"}`;
the row's `sha256` is that same raw hash. All four summary facts must be false.
An interface source never stands in for a native-bearing CBD product.

One shared top-level `interfaceInputs` list contains exact `{path, sha256}`
records for the helper, selected `settings`, package-cache files and every
interface in the checked registered hydration closure. This includes cold and
thin dependency interfaces; it does not invent missing executable providers.
Production acquisition still publishes each resolved runtime dependency through
its selected provider. Unit and module identities remain GHC's original ones.

Because these descriptors authorize a helper process, the host request always
binds the manifest's complete bytes, regardless of `verifyArtifacts`. Demand
requires an entered context with explicit process permission. The helper's
successful CBD is context-private, reuses existing admission and binding demand,
and is deleted after its readers close. Every conversion verifies the complete
input snapshot before and after helper execution. Retained file identities and
line/column spans remain available; source text and derived UTF-16 offsets are
omitted, avoiding unsnapshotted source-text reads. Offline CBD readers and the
prelaunch auditor reject this initial mode; use `required` or `pinned` for those
consumers. See [the driver limits](driver.md#installed-library-core).

## Typed lazy loading

CBD contains its own fixed fingerprint directory. Selected bindings decode from
typed DATA records; source/name maps remain independent and demand-read. Normal
loading checks framing and accessed records without a whole-body scan. Optional
verification hashes the same immutable snapshot subsequently used for execution.
JSON remains valid for manifests, launch controls and explicit inspection output,
never for executable Core input.

For both backends, eligible lifted top-level functions and thunks decode body
fields and lower executable roots on demand. Entry selection prepares the entry;
a later first global read prepares its callee. Strict globals and top-level
literals, constructors and void values retain eager initialization. Preparing a
thunk does not evaluate its Haskell computation. Immutable source projections
may be shared by an Engine, while executable roots, CAFs and guest failures stay
with each Context. Runtime admission and lowering still reject unsupported code
when preparing a binding (`reject-at-binding-admission`); the separate
whole-program audit is unchanged.

## Package-native components

Typed `packageNativeLink` metadata retains actual ABI entries, complete component
bytes, declared dependency descriptors and optional native-library companions.
The same component identity is shared by typed calls and native provider use;
dependencies do not invent Haskell ABI authority or duplicate provider globals.
These records are encoded in the CBD header and preserve the ordinary linking,
ownership and native-access rules described by the binary format.

Unresolved archived foreign products remain explicit failures, not executable
claims. Retired `entryResolution` and `availableEntries` admission protocols are
rejected; there is no per-symbol availability fallback.
