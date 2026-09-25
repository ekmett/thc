# Embed THC on the JVM

THC's current host boundary is a GraalVM polyglot `Context` and its returned
`Value`s. `thc.executionContext` and `thc.loadEntry` are the small experimental
entrypoints used by the JVM launcher. Their signatures and Kotlin/Java source
links are in the **Runtime** reference navigation.

Use the repository's pinned GraalVM and JVM dependencies. There is no published,
version-stable embedding SDK yet. Public classes in `thc.runtime` exist for
Truffle specialization, Java/Kotlin interoperability, and generated nodes;
their visibility does not make them supported host APIs.

## Load an accepted scalar entry

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

The scalar host path is integer-only. It does not marshal arbitrary Haskell
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
