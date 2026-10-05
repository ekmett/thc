# Foreign imports and exports

THC runs package C and C++ through Sulong and links their declared native
libraries. Use the ordinary Cabal workflow for native imports; use the
[managed export API](site/embedding.md#call-a-declared-haskell-export) to call
Haskell from Java. Acquiring Core preserves foreign declarations and generated
stubs, but execution also needs a supported calling convention and valid linkage.

## Run a package with native imports

Build THC with the [pinned toolchain](../README.md), then run the package through
the [Cabal driver](driver.md). Keep its C/C++ sources, headers, `extra-libraries`,
library directories and linker options in its Cabal configuration. THC uses that
configuration to compile the package sources and GHC-generated CAPI wrappers to
LLVM; external libraries are resolved by the native linker.

```sh
export THC_ROOT=/absolute/path/to/thc
export THC_CLANG=/path/to/llvm/bin/clang
export THC_LLVM_LINK=/path/to/llvm/bin/llvm-link
export THC_LLVM_OPT=/path/to/llvm/bin/opt
export THC_LLVM_NM=/path/to/llvm/bin/llvm-nm

# Run from the application's Cabal project.
"$(cabal list-bin exe:thc --project-dir="$THC_ROOT")" run my-program \
  --thc-root "$THC_ROOT" --installed-core required \
  --ghc-source /path/to/matching/configured/ghc-9.14.1
```

The LLVM tool variables are optional when matching tools are on `PATH`.
Acquisition needs them even for a pure Haskell package: boot libraries with
native imports, such as `ghc-internal`, are compiled to LLVM bitcode. Cabal's
native build still uses its selected GHC and native compiler; LLVM acquisition
is a separate step.
Use `thc acquire` with the same build options to prepare the package without
executing it. Add `--verify-artifacts` to `thc run` for a strict pre-launch Core
audit and artifact-hash verification.

Haskell FFI uses native-enabled Sulong. Embedding contexts must permit native
access. These calls are not a sandbox for
untrusted C code.

The package linker supports ELF on Linux and Mach-O on Darwin. Generic package
linkage on Windows remains unsupported; see the narrower
[Windows workflows](windows.md). Missing symbols and libraries are native linker
errors: install the dependency and correct the package's declared link settings.
THC does not load native Haskell archives or a host GHC RTS to execute guest Core.

## Supported calls

| Declaration or product | Current behavior |
| --- | --- |
| Static `ccall` and `capi` | Integer, Float, Double, address and byte-array arguments; numeric, pointer or void result. CAPI value imports use GHC's generated wrapper. |
| `unsafe` calls | Synchronous execution; reentry into Haskell is rejected. |
| `safe` calls | Release guest scheduling admission during the call and permit supported callbacks. Pending asynchronous delivery occurs after return, without repeating the native effect. |
| `interruptible` calls | General native interruption is unsupported. |
| `dynamic` and `wrapper` imports | Scalar/address function signatures with the callback limits below. |
| Static `foreign export ccall` | Package C can call declared scalar/address exports in the same THC context, including during native component initialization. |
| C++ sources | Configured `.cc`, `.cpp` and `.cxx` sources, including their native dependencies. Constructors run when the context loads the component; normal context close runs registered destructors. Forced cancellation does not guarantee cleanup. |
| Assembly sources | Unsupported. |

Narrow signed and unsigned integers keep their declared C widths. Pure Haskell
imports and IO imports both use GHC's emitted calling convention. Calls preserve
the guest thread's `errno`; native return values and error codes remain the
library's responsibility.

Package LLVM globals belong to their THC context. External native libraries may
have process-wide state. THC supplies special adapters where a call must act on
its own scheduler, managed files, environment or memory; ordinary package C is
not automatically virtualized.

Acquisition omits native wrappers for GHC calls verified against THC runtime
handlers. This does not make those handlers callable from C. Native C
references, public definitions, constructors, address imports and finalizers
still need real native providers, including when they use the same symbol.
Other RTS imports need their own implementation.

Compatible ordinary `ccall` imports load their captured adapters on first use.
Unused imports need no adapter loading. Calls share one native provider per
context, preserving its C globals and constructor state. This path needs no
compiler subprocess or writable adapter cache at runtime. A reached import
without its declared provider fails at the call; that failure persists for the
lifetime of the context.

Adapter selection requires a matching target and C ABI. Other imports use the
normal eager-linking path. Linux execution is validated; native macOS validation
of this path remains pending.

This narrow path does not defer native C references, public exports,
constructors, addresses or finalizers, or admit unknown LLVM definitions and
ABIs. Those products retain their existing strict linkage and validation.

Concrete lifted or unlifted GC-boxed declarations can retain verified stock
import provenance, including boxed results. That evidence is not a native ABI:
GC-boxed imports remain excluded from package-native adapters, and are never
rewritten to `AddrRep` or passed as native heap pointers. The narrow owned-thread
operations described in [managed thread status](thread-status.md) use exact
nominal declaration and call-inventory admission plus context/lifetime checks.
Ordinary library imports still use their declared Sulong linkage.

GHC's floating classification helpers and `rintFloat`/`rintDouble` use ordinary
package-declared native linkage; THC supplies no replacement arithmetic.
[PackageNativeForeignTest](../src/test/java/thc/runtime/PackageNativeForeignTest.java)
checks signed zeros, finite representations and special values through the shared
Float/Double import boundary.
This component test does not qualify full Core lowering with the exception runtime
or the installed GHC floating/math package, including its rounding behavior.

## Pass buffers and pointers

A managed Haskell buffer is not a native machine address. Choose storage that
matches the library's needs:

- Package LLVM code can operate on supported managed `ByteArray#` and
  `MutableByteArray#` views. Aliases share storage; immutable views reject writes.
- Explicitly pinned arrays have stable native backing shared by Haskell,
  Sulong and host C. Ordinary unpinned arrays cannot be projected into host-native
  pointers, including after unsafe freeze. Calls do not automatically copy or pin
  them.
- Native pointer results can be passed back to C. A pointer with no known extent
  does not grant Haskell permission to read or write its target. The external
  library's ownership and release rules still apply.
- `StablePtr` values are context-owned opaque identities. C may store and return
  them while they remain live; Haskell `freeStablePtr` or C `hs_free_stable_ptr` releases them. They are not GHC RTS
  heap pointers, and do not support the C `hs_deref_stable_ptr` ABI.

Known allocation aliases retain their owner and lifetime checks. Do not use a
pointer after its owner is freed or its context closes, or pass it to another
context. An arbitrary integer converted to `Addr#` does not gain native access
or deallocation authority. Managed Sulong pointer results and general retained
moving-buffer contracts are unsupported.

Two imports of one C symbol may use different supported carriers when their C
ABI agrees. Conflicting prototypes are rejected. If mutable and immutable
byte-array declarations erase to the same call shape, the runtime cannot infer
which access policy to use; that ambiguous form is unsupported.
See [pinned memory](pinned-memory.md) and [native addresses](native-addresses.md)
for the storage contracts.

When GHC inlines a native function or data address into another package, lazy
loading finds its original address declaration in that package's declared
dependency closure. It checks the component's normal provenance without
evaluating the original Haskell wrapper; ambiguous declarations remain errors.

## Call Haskell from native code or Java

For C callbacks, use a genuine `foreign import ccall "wrapper"` declaration and
release the result with `freeHaskellFunPtr`. THC retains the native closure and
its guest function until explicit release or context close. `dynamic` imports
use the declared invocation signature; a function-address getter alone does not
determine that signature.

Callbacks from an active foreign call require a supported safe call. They start unmasked;
unsafe reentry is rejected. Reentry is also rejected while managed pointer cells
await native writeback. General cross-thread callbacks and aggregate callback
ABIs are unsupported. Keeping raw pointer bits does not extend a callback's
lifetime.

Package C can also call declared `foreign export ccall` functions by their
exported symbols. THC registers context-owned targets before running native
constructors, so a constructor may call Haskell and obtain its component's
native labels. If initialization fails, later lookup or invocation of its
finalizers reports that failure; initialization is not retried. Arbitrary native
side effects are not rolled back.

Retained original CAPI bitcode uses the same callback-before-constructor stage,
including prepared AST loads. Library identity and symbol ownership are reserved
before native effects. Failed initialization is remembered for later linking and
calls; a conflicting library cannot run its constructor. Recursive demand for
the same CAPI library while its constructor is still running fails instead of
waiting on itself. This does not broaden the admitted CAPI ABI or registration
metadata.

Native component links can retain package-declared C providers separately from
their Haskell-call ABI. Consumers share one initialized provider per context;
the provider's public C definitions do not require invented Haskell imports.
Dependencies initialize before consumers. A constructor callback can re-enter
its own initialized native symbols, but cannot enter a pending ancestor whose
dependencies are still loading: that lookup fails rather than waiting on
itself. General cross-component initialization cycles are not supported.

Package C's `rtsSupportsBoundThreads` query uses the current THC context, not a
native GHC RTS. It returns false, matching the guest scheduler's existing
bound-thread capability; its C `HsBool` result has the machine-word ABI.

For Java callers, the same declarations expose scalar and IO functions through
`thc.Main.loadManagedExports`. Follow the
[embedding example](site/embedding.md#call-a-declared-haskell-export) for supported
types, namespace lookup and context lifetime. Java receives polyglot functions;
package C uses the native callback binding. Both refer to the owning context's
Haskell functions, without a separate host GHC RTS.

Catchable foreign language failures use the
[foreign-exception bridge](foreign-exceptions.md). This does not transport
arbitrary C++ exceptions across a plain C ABI or make native error codes into
exceptions automatically.

## Acquire installed foreign declarations

Source acquisition records the typed import evidence required for linkage.
Managed static exports additionally require the plugin options
`foreign-export-associations` and `foreign-export-registration`; see the
[Core exporter](compiler.md). Retained C stubs alone do not establish which
Haskell function and type a symbol represents.

For installed GHC libraries whose interfaces lack the required annotations,
use `--installed-core required --ghc-source DIR`. `DIR` must be the matching
configured GHC 9.14.1 native Linux stage1 tree, with its built interfaces,
Cabal setup configuration, generated sources and headers intact. THC can produce
the missing annotations for selected `ghc-internal` and `unix` modules. It checks
that their sources, interfaces and header inputs match the selected installation
and caches the resulting acquisition view without changing installed files.

A source tarball, thin interfaces, cross compiler, or mismatched configuration
is insufficient. Follow [GHC library Core](ghc-core.md) to prepare a complete
installation. Missing annotations can be generated by this supported path;
present but invalid evidence remains an error.

## Diagnose a rejected import

| Failure | Action |
| --- | --- |
| Missing Clang or LLVM tool | Install matching tools or set the `THC_*` variables above. |
| Unresolved native symbol/library | Check Cabal's external libraries, search paths and linker options, including transitive native dependencies. |
| Missing complete Core or typed foreign evidence | Reacquire with the matching complete-Core compiler and configured source tree. |
| Changed source/header, hash or ABI mismatch | Rebuild and reacquire from the actual selected configuration; do not amend metadata to bypass validation. |
| Unsupported declaration or conflicting C prototypes | Correct the declaration when erroneous; otherwise the calling-convention gap needs runtime support. Successful acquisition alone does not make it executable. |
| Native pointer projection or callback reentry rejected | Check pinning, lifetime, context ownership and `safe`/`unsafe` selection against the contracts above. |
| `Unsupported foreign call: SYMBOL` | The reached call has no implementation or valid native link. Supply the package linkage or supported runtime boundary; diagnostic mode does not implement the call. |

Unsupported package-native declarations may be retained for inspection while
supported imports in the same component remain usable. A reachable excluded call
is rejected before evaluation. Unresolved initializers, finalizers and additional
foreign files can block the whole component because their effects do not depend
on which Core binding is called.

## Artifact contract for tool authors

Use the producer's typed declarations and emitted ABI; never derive a calling
convention from pretty-printed types or stub text. Import/export evidence ties
the original unit, module and binder to the emitted symbol. Linked artifacts
also identify their LLVM target, content hash and build inputs. Inlined calls
must resolve against their owning component's declaration inventory.

Retained foreign products preserve ordered stubs, lifecycle entries and source
contents. Their presence records obligations; it is not execution permission.
The loaders and auditor validate ownership, signatures and linkage independently
of optional whole-file hash verification. Keep unknown declarations distinct
from a proven empty inventory. See the [Core format](compiler.md),
[package manifest](core-package-manifest.md), and
[binary format reference](compact-core-format.md#retained-import-and-export-provenance)
for the serialized contracts.
