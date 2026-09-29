# Embed THC on the JVM

THC's current host boundary is a GraalVM polyglot `Context` and its returned
`Value`s. `thc.Main.loadManagedExports` exposes declared Haskell functions as polyglot
members. `thc.Main.executionContext` creates a launcher context, while `thc.Main.loadEntry`
provides the lower-level kernel and executable entrypoints. Their signatures and Java source
links are in the **Runtime** reference navigation.

Use the repository's pinned GraalVM and JVM dependencies. There is no published,
version-stable embedding SDK yet. Public classes in `thc.runtime` exist for
Truffle specialization and generated nodes;
their visibility does not make them supported host APIs.

## Call a declared Haskell export

A `foreign export ccall` declaration supplies the external name and scalar
signature. THC can expose that declaration through Truffle interop, so a Java
caller gets ordinary polyglot values:

```haskell
foreign export ccall "thc_add_one" addOne :: Int32 -> Int32
foreign export ccall "thc_next" next :: Int32 -> IO Int32
```

The repository's `ForeignExportManaged` fixture includes these declarations.
After preparing the `interface-core` fixtures, its retained Core can be loaded
as follows:

```java
import java.util.List;
import static thc.Main.executionContext;
import static thc.Main.loadManagedExports;

void main() {
    try (var context = executionContext()) {
        var units = loadManagedExports(context,
            List.of("build/interface-core/typed-foreign-exports/managed.json"));
        var exports = units.getMember("thc-interface-fixture-0.1")
            .getMember("ForeignExportManaged");

        if (exports.getMember("thc_add_one").execute(41).asInt() != 42) throw new AssertionError();
        if (exports.getMember("thc_float").execute(1.25f).asFloat() != 2.25f) throw new AssertionError();
        if (exports.getMember("thc_next").execute(3).asInt() != 3) throw new AssertionError();
        if (exports.getMember("thc_next_alias").execute(4).asInt() != 7) throw new AssertionError();
    }
}
```

The namespace is **unit → module → declared C symbol**. It is also available
through `context.getBindings("thc")`. Aliases share their Haskell function and
CAF state. Calling an `IO` export runs the action and returns its scalar result;
argument validation finishes before the action starts.

The same API accepts `List.of("@/absolute/path/to/packages.json")` for direct
unit artifacts. Registration-bearing modules supply the exported roots and
their checked signatures; unrelated units remain unopened. The parsed load
request can be shared by an Engine, but registrations, decoded code and CAFs
belong to each executing context. A failed load publishes no namespace.

Accepted signatures use boxed `Int`, `Word`, their fixed-width variants,
`Float`, `Double`, `Bool` and `Char`, with `()` additionally allowed as a result.
Integral arguments are range-checked. Use `BigInteger` and `Value.asBigInteger()`
for the upper half of `Word64`; a unit result has `Value.isNull() == true`.
These are the signatures admitted by the declared C-export path. The lower-level
Core entry path below also transports guest references, functions and raw SIMD
values; that does not expand the native C calling convention of an export.

The namespace values are managed entrypoints, not host-visible native addresses.
Original package C can resolve the same declared exports through the context's
native callback namespace when native access is enabled. `Ptr`, `FunPtr` and
context-owned `StablePtr` arguments/results use their checked address codecs;
they never reinterpret an arbitrary Java reference as a native address.
The loader requires the typed declarations and verified retained registration
products produced with THC's `foreign-export-associations` and
`foreign-export-registration` plugin options. The current producer profile is
for GHC 9.14.1 static `ccall` exports; an old interface containing only C stubs
does not supply that evidence. Mixed C imports retain their independently
verified import-only products; unclassified
registration remain unsupported. One managed bundle can be loaded per context;
its members are read-only and live only as long as that context.

## Load a Core entry

```java
import java.util.List;
import static thc.Main.executionContext;
import static thc.Main.loadEntry;

void main() {
    try (var context = executionContext()) {
        var function = loadEntry(context,
            List.of("/absolute/path/to/Module.json"), "sumLoop", true, "bytecode");
        long result = function.execute(100_000L).asLong();
        System.out.println(result);
    }
}
```

`loadEntry` uses the entry's retained Core signature. Host values follow its
logical argument and result shapes, not the runtime's flattened transport slots:

| Core representation | Host value |
| --- | --- |
| Signed and unsigned integer primitives | Exact, range-checked numbers; use `BigInteger` / `asBigInteger()` for the upper half of `Word64#` |
| `Float#`, `Double#` | Exactly representable numbers; negative zero and non-finite values are retained |
| Unboxed tuple | An array of logical fields, recursively; an empty tuple is an empty array |
| Unboxed sum | A two-element array `[tag, payload]`, with a 1-based alternative tag |
| SIMD vector | The exact raw JDK Vector API value with the declared species; not a lane array |
| Guest reference or managed address | An opaque, context-owned value returned by THC; null is accepted for a null address |
| ByteArray# storage | A read-only, aliasing buffer with byte-order-aware reads; never an invented native pointer |
| Array# / SmallArray# storage | A fixed-size, read-only array view; only the requested element is exported, without forcing it |
| Guest function | An executable, context-owned value, including closures and partial applications |
| `State#` / `Void#` | Null; nested void fields occupy a logical field but no physical register |

Tuple and sum results expose read-only array elements. They do not retain a view
of temporary guest argument or result pools. Raw vector inputs require host-object
access, for example `Context.newBuilder("thc").allowHostAccess(HostAccess.ALL)`;
`result.asHostObject()` returns the raw JDK vector without lane boxing or species
conversion. A same-width vector of another lane type is not interchangeable.

References cannot be fabricated from arbitrary Java objects. Managed addresses
do not expose pointer bits, and integer arguments are not implicitly treated as
addresses. Guest references and functions cannot be passed to another context
or used after their context closes. Host marshalling does not force references
or inspect their fields; the guest's own evaluation rules still apply. Calling a returned function checks its remaining logical
signature, including any already supplied partial-application prefix.

Storage views retain their original allocation. An owned byte or small-array
shrink is visible through the existing view; growing to a different allocation
does not retarget old views. Ordinary Core export is read-only because erased
RuntimeRep cannot distinguish mutable and immutable storage. Typed
[`THC.Interop.Buffer` constructors](../polyglot.md#buffers-and-fixed-arrays)
can explicitly grant byte writes. Arbitrary addresses have no known extent and
are not buffers. Pointer-bearing managed allocations cannot be exposed as raw
bytes, and raw-exposed allocations reject later managed pointer-cell stores.

Qualified constructor identities and their complete field contracts are shared
across independent loads in one context, including between AST and bytecode
entries. Different field contracts, unqualified synthetic constructors and
separate contexts remain distinct. The host transport preserves the original
data identity; it does not copy or rebox values to fit a different layout.

Use an entry whose retained signature matches the supplied arguments. Missing
legacy scalar evidence does not authorize guessing an aggregate or function
signature. `backend` selects `bytecode` or `ast`
when loading; the default comes from `thc.backend`, then `THC_BACKEND`, then
`bytecode`.

The separate `bin/audit-core.py` command still rejects vector, tuple and
sum entry signatures. Its scalar-entry fixture frontiers do not describe the
logical polyglot ABI above; `loadEntry` does not invoke that auditor. The
command-line scalar runner still parses integer arguments. Native C exports,
C callback pointers and guest exception/masking rules retain their separate
contracts.

For a package closure, pass a singleton list containing
`"@/absolute/path/to/packages.json"`. Artifact hashes are checked only with
`verifyArtifacts = true`; identity, ownership and calling-convention checks
still apply when code is admitted. See the [manifest guide](../core-package-manifest.md).
Individual JSON paths are a lower-level development input; assembling a list
does not establish package support.

### Indexed packages and loose inputs

The Cabal driver writes JSON and symbol directories. Serialized `.idx` sidecars
have been retired. [JSON navigation](../core-package-manifest.md#json-navigation-and-lazy-loading)
can build an in-memory index while leaving eligible binding bodies unmaterialized.
Use the request builder's `indexed` flag for explicit loose JSON inputs:

```java
import java.util.List;
import thc.CoreModules;
import static thc.Main.executionContext;

void main() {
    try (var context = executionContext()) {
        String json = "/absolute/path/to/Module.json";
        var request = CoreModules.request(
            List.of(json, "@/absolute/path/to/support.json"), "sumLoop",
            true, false, "bytecode", true, false, null, null, true);
        var function = context.eval("thc", request);
        System.out.println(function.execute(100_000L).asLong());
    }
}
```

The request accepts distinct loose JSON paths and at most one `@` package
manifest. The manifest supplies dependencies without also supplying the same
consumer module. Omit that input when no support package is needed. Deferred
file reads remain authorized by the host request builder; guest requests cannot
forge file paths or change artifact-verification policy.

For an accepted standalone `IO ()` entry, the JVM launcher accepts:

```sh
build/install/thc/bin/thc \
  --run-io /absolute/path/to/Main.json,@/absolute/path/to/support.json \
  app-unit:Main.main -- app --help
```

The literal `--` introduces `PROGRAM_NAME ARG...`; all following arguments
belong to the guest. Whole-source hashing requires explicit artifact verification.
Source scanning, header indexing and dependency discovery still do work during
loading. Loading an entry does not establish support for every cold binding;
the separate execution audit checks that scope.

## Execute `IO ()`

The [Cabal driver](../driver.md) prepares and audits the executable closure,
then uses the separate `ioMain = true` entry contract. A loaded IO action has a
`runIO` member; invoke it and check its Boolean completion result. Do not call
that action through the scalar `execute(Long)` convention. Full executable
launches also supply their distinct shutdown entry; a standalone IO action
does not imply Handle flushing or general executable lifecycle support.

`executionContext(true)` requests host file IO. On supported native
hosts it selects the command-line native IO provider; otherwise the context
still receives full polyglot file IO authority. The factory also permits native
access and guest threads. It is a launcher convenience, not an isolation policy
for untrusted code. Applications needing different authority should construct
their own polyglot context after inspecting THC's actual provider requirements.

## Context and value lifetimes

Keep the context open while using its values, and close it once the work is
finished. Heap state, thunks, thread identities, and native resources belong to
that context. Do not cache guest values across closed contexts or use runtime
carriers as a cross-context interchange format.

Compilation and metrics members are development controls, not evidence that
every reachable operation is supported. Keep diagnostic unsupported traps out
of accepted executable runs. `loadEntry` disables that diagnostic option for
the `ioMain` path.

For guest calls into other languages, see [polyglot calls](../polyglot.md).
Core acquisition through `THC.Plugin` or `THC.Interface` is a different boundary;
success there does not imply that a module's foreign products are executable.
