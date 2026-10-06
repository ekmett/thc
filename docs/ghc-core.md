<!-- SPDX-FileCopyrightText: 2026 Edward Kmett -->
<!-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause -->

# GHC library Core

THC needs function bodies, including private workers and `OPAQUE` definitions.
An ordinary installed `.hi` file need not contain them. GHC already has the
[option to retain every binding](https://downloads.haskell.org/ghc/9.14.1/docs/users_guide/phases.html#ghc-flag-fwrite-if-simplified-core):

```cabal
ghc-options: -fwrite-if-simplified-core
```

Hadrian already accepts this option through its
[settings file](https://github.com/ghc/ghc/blob/ghc-9.14.1-release/hadrian/doc/user-settings.md).
Append the supplied [configuration](../etc/ghc/9.14.1/core.settings) to
`<build root>/hadrian.settings` (normally `_build/hadrian.settings`):

```text
*.*.ghc.hs.opts += -fwrite-if-simplified-core
```

This applies to Haskell compilation across packages and stages, including
`ghc-internal`, `base`, `template-haskell`, its lift/quasiquoter libraries, and
the compiler itself. It also retains Core in program and bootstrap interfaces;
C compilation and linking are unaffected. It does not require a source patch
or change Haskell definitions or inlining decisions. The aggregate interface-size
cost has not been measured.

An upstream release configuration can use the same setting. The optional
[Hadrian library patch](../nih/patches/ghc-9.14.1/ghc-libraries-simplified-core.patch)
implements a narrower built-in default for library packages after bootstrap.
It covers `ghc-internal`, `base`, `template-haskell`, and every other library
without maintaining a package list. The patch is not needed with the settings
above.

## Check an installation

```sh
make check-ghc-core GHC=/path/to/ghc
make check-ghc-core CORE_PACKAGES='base containers text' GHC=/path/to/ghc
```

The default checks `ghc-internal` and `base`; `CORE_PACKAGES` selects other
installed packages. The check includes their registered transitive package
dependencies and reads each Haskell interface's complete-Core field through
the selected compiler's GHC API, in one process. Native-only registrations
have no Haskell interfaces to inspect.
Recognizing the command-line flag is insufficient: the consumed library must
have been built with it. Keep the matching compiler tools together; a
`ghc-pkg` from another installation must not supply the package being checked.
Run the check for each library whose bodies THC will load.

This is a capability check, not a declaration of GHC API compatibility.
The exporter currently supports GHC 9.14.1. The project driver already supports
`--installed-core required`, which resolves exact selected registrations and
acquires their complete interfaces through `thc-interface`. The default
`--installed-core pinned` remains a separate source-provider choice, not a
fallback after an interface error. The single-package `.cabal` path supports
only the pinned mode. See the [driver guide](driver.md) for acquisition, checked
ZIP caching and target-layout receipts. The normal build reports missing Core
capability without disabling pinned mode; the explicit `check-ghc-core` target
fails when the required data is absent.

## Library loading API

`THC.Interface` exposes the GHC 9.14.1 adapter independently of the generic
driver executable:

```haskell
loadInterfaceCore :: HscEnv -> Module -> FilePath -> IO (Maybe InterfaceCore)
interfaceCoreCBD :: [CommandLineOption] -> InterfaceCore -> IO ByteString
interfaceCoreJSON :: [CommandLineOption] -> InterfaceCore -> IO String
```

Use `interfaceCoreCBD` for executable output; `interfaceCoreJSON` provides
explicit diagnostic inspection.

Resolve the expected `Module` (including its exact package unit) and interface
path using the selected compiler session and package databases. `Nothing`
means a valid interface lacks complete Core; wrong module/unit identities,
way/version mismatches and malformed interfaces are errors. Nonempty foreign
stubs and foreign files are retained in [foreign metadata](interface-foreign.md#artifact-contract-for-tool-authors),
including exact source and initializer/finalizer identities. Hydration does not
link native products or register foreign exports. The checked loader rejects
unlinked global lifecycle obligations even outside the entry's reachable
closure, and rejects reachable bindings owned by an unlinked archive module.
Verified native links have their own admission checks; unrelated archival
bindings do not grant executable status. Ordinary foreign calls still require
the runtime's normal support audit.

The result exposes the original module, `ModDetails`, `CoreProgram` and foreign
metadata. Installed-Core acquisition converts each module in an isolated helper
and retains serialized bytes rather than decoded GHC trees.

Executable IDs use GHC's mangled occurrence spelling. In GHC 9.14, a record
selector such as `field` has a constructor-qualified namespace represented by
`$fld:Constructor:field`; it is distinct from another constructor's selector or
an ordinary exported alias named `field`. Display names remain unchanged.

Hydration reads the raw interface before GHC's package cache strips
complete Core. It privately retains interface pragmas/source ticks and keeps
existing knot lookups for other modules. Use a fresh session retaining pragmas for dependency
loading; the loader does not repair previously discarded dependency unfoldings.
Cross-module home-package/hs-boot cycles are not yet an integration-tested use.

GHC 9.14.1 writes this payload before `AddImplicitBinds` injects constructor
wrappers. The loader therefore also supplies missing, locally owned boxed-data
wrappers from the hydrated declarations' genuine `DataConWrapId` unfoldings,
with their original names, types and coercions. Existing binding groups remain
unchanged; wrappers are separate nonrecursive groups. This is not a fallback to
ordinary function unfoldings when complete Core is absent. Constructor workers
remain represented by constructor metadata, and newtypes are not injected.
Serialization shares `THC.Plugin.serializePostTidyCore` with the late plugin,
including exact recursive groups, representations, existing CBV proofs and
optional `source-notes`/`unit-qualified` metadata. No source target is required;
missing source text stays absent. The driver owns acquisition and cache
integration separately; this API does not link dependencies or establish runtime
support for an entire package.

## Selected-compiler helper

Build `exe:thc-interface` with the selected compiler, separately from the generic
driver's GHC-independent process. Invoke the helper directly (not `cabal run`,
whose build messages are not part of the protocol):

```sh
cabal build exe:thc-interface --with-compiler=/path/to/ghc
cabal list-bin exe:thc-interface --with-compiler=/path/to/ghc
/path/to/thc-interface --libdir /path/from/selected-ghc-print-libdir \
  --unit exact-installed-unit-id --module Package.Module \
  --interface /path/to/Package/Module.hi --package-db /path/to/package.conf.d \
  --way vanilla --source-notes > Package.Module.cbd
```

`--libdir`, `--unit`, `--module` and `--interface` are required. Package databases
may be repeated in GHC stack order. `--source-spans` preserves retained file
identities and line/column spans without reading source files; `--source-notes`
keeps its existing optional text and UTF-16 offset capture. These flags are exclusive. The stack is explicitly the selected libdir's
global database plus those arguments: implicit user databases and package
environments are disabled. The helper does not discover packages, rebuild them,
or run guest code. The caller must select a helper built against the same GHC
API/installation as that libdir; changing libdir is not GHC API compatibility.
`--unit` is the exact registered package ID. The selected GHC `UnitState` maps it
to an interface owner (for example, a versioned `ghc-internal` registration to
the canonical `ghc-internal` owner) and must map back to that exact registration.
Core retains its original canonical identity; no version/hash stripping or
canonical-name alias is accepted in place of an exact registered ID.
Ways are `vanilla` (default), `dynamic`, or `profiling`; the interface header must
match the requested way. Only vanilla/dynamic synthetic packages are tested.

For a single-module load, successful stdout is the raw
[CBD container](compact-core-format.md). Missing-Core and failure responses are
one UTF-8 JSON object with `schema: 1`:

| Exit | Stdout | Payload |
| --- | --- | --- |
| 0 | Binary CBD | Complete serialized module |
| 3 | JSON | `status: "unavailable"`, `capability: "complete-interface-core"`, `unit`, `module`, `way`, `interface`; no Core |
| 1 | JSON | `status: "error"`, `category: "interface"`, `message`; no Core |
| 2 | JSON | `status: "error"`, `category: "usage"`, `message`, `usage`; no Core |

The helper finishes serialization into a strict `ByteString` before writing
successful output. Capture stdout as bytes and check the exit status before
treating the result as CBD. Keep stderr separate from the payload.
Cancellation is not converted to a missing-capability result. An unavailable
result never substitutes inline unfoldings. In the driver's installed-Core
required mode it is a capability failure; choosing the pinned source provider
is an explicit option, not a retry policy.

The helper converts one requested interface. The default `pinned` and explicit
`required` providers convert every module in each selected installed registration
before runtime binding demand. The opt-in `demand` provider instead publishes
checked retained-interface descriptors for whole units whose startup, native,
main-alias and delimited-control facts are all provably false. GHC decodes the
interface syntax for eligibility without hydrating or serializing every module.
The private inventory protocol adds Boolean `demandEligible` alongside
`completeCore`; false eligibility selects ordinary CBD/native acquisition, while
missing complete Core in a requested unit fails acquisition.

The JVM invokes this same helper only when a selected module is requested, then
uses the existing compact reader and checks the emitted header facts against
those recorded in the directory. It does not parse GHC binary interfaces.
A separate [native interface module path](core-package-manifest.md#native-interface-modules)
now decodes a bounded scalar subset directly into the shared runtime, without a
GHC helper or intermediate CBD. It executes retained private functions,
recursion and cross-module integer calls on both backends and handoff modes.
It is not yet a general installed-library loader; [#1063](https://github.com/ekmett/thc/issues/1063)
remains open for declarations, types, expression coverage and driver integration.
[Demand eligibility and limits](driver.md#installed-library-core) describe the
process permission, source snapshot and explicit offline-audit restriction.

## Build a compiler with complete Core

These commands build THC's supported GHC 9.14.1 release. Use a
separate build directory and installation prefix. Install that release's
[GHC build prerequisites](https://gitlab.haskell.org/ghc/ghc/-/wikis/building/preparation)
first. GHC 9.14.1's `configure.ac` requires a bootstrap GHC of at least 9.6;
other releases may require a different bootstrap compiler.

From the THC repository, record its configuration location and select the bootstrap:

```sh
THC_SOURCE="$PWD"
THC_BOOT_GHC=$(command -v ghc)
THC_GHC_VERSION=9.14.1
THC_GHC_PREFIX="$HOME/.local/ghc/$THC_GHC_VERSION-core"

mkdir ghc-core-build
cd ghc-core-build
curl -fLO "https://downloads.haskell.org/ghc/$THC_GHC_VERSION/ghc-$THC_GHC_VERSION-src.tar.xz"
tar -xf "ghc-$THC_GHC_VERSION-src.tar.xz"
cd "ghc-$THC_GHC_VERSION"
mkdir -p _build
cat "$THC_SOURCE/etc/ghc/9.14.1/core.settings" >> _build/hadrian.settings

test -f configure || ./boot
GHC="$THC_BOOT_GHC" ./configure --prefix="$THC_GHC_PREFIX"
GHC="$THC_BOOT_GHC" ./hadrian/build -j4 --flavour=perf --docs=none \
  install --prefix="$THC_GHC_PREFIX"
```

The bootstrap compiler builds the pinned release; its version does not select
the target release. This follows GHC's [Hadrian build and installation procedure](https://gitlab.haskell.org/ghc/ghc/-/blob/ghc-9.14.1-release/hadrian/README.md).
Use the release's published checksum or signature to verify the source archive.
Allow enough disk space for a compiler build; the source archive is much smaller
than the working set. For a nondefault build root, put `hadrian.settings` in that
directory instead. The append preserves any settings already there.

Then select the new installation consistently:

```sh
export PATH="$THC_GHC_PREFIX/bin:$PATH"
cd "$THC_SOURCE"
make check-ghc-core GHC="$THC_GHC_PREFIX/bin/ghc"
make GHC="$THC_GHC_PREFIX/bin/ghc"
```

Passing the same compiler to Cabal directly is `cabal build --with-compiler=/path/to/ghc`.
Changing the selected GHC must select its matching exporter build and package
database; cache entries also remain separated by target and compiler identity.

## Patch scope

The optional Hadrian patch selects Haskell compilation of library packages
after Stage0, for every built library way. It includes GHC's compiler library
as well as ordinary libraries. The settings-file recipe above does not require
the patch and also covers program and bootstrap compilation.

The Hadrian flag only covers libraries built by that GHC source tree. Ordinary
project packages can emit Core when THC builds them; independently installed
packages require their own complete-Core build. `rts` C code and primop
semantics are separate from Haskell interface payloads. GHC API changes remain
explicit compatibility work; retaining library Core removes one source of
version-specific scaffolding, not those obligations.
