# Embed THC on the JVM

THC's current host boundary is a GraalVM polyglot `Context` and its returned
`Value`s. `thc.loadManagedExports` exposes declared Haskell functions as polyglot
members. `thc.executionContext` creates a launcher context, while `thc.loadEntry`
provides the lower-level kernel and executable entrypoints. Their signatures and Kotlin/Java source
links are in the **Runtime** reference navigation.

Use the repository's pinned GraalVM and JVM dependencies. There is no published,
version-stable embedding SDK yet. Public classes in `thc.runtime` exist for
Truffle specialization, Java/Kotlin interoperability, and generated nodes;
their visibility does not make them supported host APIs.

## Call a declared Haskell export

A `foreign export ccall` declaration supplies the external name and scalar
signature. THC can expose that declaration through Truffle interop, so a Java or
Kotlin caller gets ordinary polyglot values:

```haskell
foreign export ccall "thc_add_one" addOne :: Int32 -> Int32
foreign export ccall "thc_next" next :: Int32 -> IO Int32
```

The repository's `ForeignExportManaged` fixture includes these declarations.
After preparing the `interface-core` fixtures, its retained Core can be loaded
as follows:

```kotlin
import thc.executionContext
import thc.loadManagedExports

executionContext().use { context ->
    val units = loadManagedExports(context,
        listOf("build/interface-core/typed-foreign-exports/managed.json"))
    val exports = units.getMember("thc-interface-fixture-0.1")
        .getMember("ForeignExportManaged")

    check(exports.getMember("thc_add_one").execute(41).asInt() == 42)
    check(exports.getMember("thc_float").execute(1.25f).asFloat() == 2.25f)
    check(exports.getMember("thc_next").execute(3).asInt() == 3)
    check(exports.getMember("thc_next_alias").execute(4).asInt() == 7)
}
```

The namespace is **unit → module → declared C symbol**. It is also available
through `context.getBindings("thc")`. Aliases share their Haskell function and
CAF state. Calling an `IO` export runs the action and returns its scalar result;
argument validation finishes before the action starts.

Accepted signatures use boxed `Int`, `Word`, their fixed-width variants,
`Float`, `Double`, `Bool` and `Char`, with `()` additionally allowed as a result.
Integral arguments are range-checked. Use `BigInteger` and `Value.asBigInteger()`
for the upper half of `Word64`; a unit result has `Value.isNull == true`.
Arbitrary algebraic data, functions and SIMD values are not host arguments yet.

This is an experimental managed entrypoint, not a native C callback address.
The loader requires the typed declarations and verified retained registration
products produced with THC's `foreign-export-associations` and
`foreign-export-registration` plugin options. The current producer profile is
for GHC 9.14.1 static `ccall` exports; an old interface containing only C stubs
does not supply that evidence. Other foreign products and unclassified
registration remain unsupported. One managed bundle can be loaded per context;
its members are read-only and live only as long as that context.

## Load an integer kernel

```kotlin
import thc.executionContext
import thc.loadEntry

executionContext().use { context ->
    val function = loadEntry(
        context,
        listOf("/absolute/path/to/Module.json"),
        "sumLoop",
        backend = "bytecode"
    )
    val result: Long = function.execute(100_000L).asLong()
    println(result)
}
```

The older `loadEntry` scalar path is integer-only. It does not marshal arbitrary Haskell
types, aggregate arguments, or closures. Use an exported entry whose accepted
contract matches the supplied argument. `backend` selects `bytecode` or `ast`
when loading; the default comes from `thc.backend`, then `THC_BACKEND`, then
`bytecode`.

For a checked package closure, pass a singleton list containing
`"@/absolute/path/to/packages.json"`. The loader verifies package identities,
hashes, boundaries, and reachable references. See the [manifest guide](../core-package-manifest.md).
Individual JSON paths are a lower-level development input; assembling a list
does not establish package support.

## Execute `IO ()`

The [Cabal driver](../driver.md) prepares and audits the executable closure,
then uses the separate `ioMain = true` entry contract. A loaded IO action has a
`runIO` member; invoke it and check its Boolean completion result. Do not call
that action through the scalar `execute(Long)` convention. Full executable
launches also supply their distinct shutdown entry; a standalone IO action
does not imply Handle flushing or general executable lifecycle support.

`executionContext(fileIO = true)` requests host file IO. On supported native
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
