# Examples

These programs demonstrate Haskell libraries and the public runtime APIs:

- [WeakThreads](WeakThreads.hs): observe a worker without owning its lifetime,
  then recover mutable state from a finalizer and continue using it.
- [RuntimeServices](RuntimeServices.hs): runtime information and thread services.
- [CpuAffinity](CpuAffinity.hs): processor affinity queries and updates.
- [PolyglotDemo](PolyglotDemo.hs): host values and calls through `THC.Polyglot`.
- [JavaScriptDemo](JavaScriptDemo.hs): JavaScript interoperation.

See the [runtime services](../../docs/runtime-services.md),
[CPU affinity](../../docs/cpu-affinity-api.md), and
[polyglot](../../docs/polyglot.md) guides for setup and commands.
The [Java examples](java/thc) show embedding from a host application;
[standard-apps](standard-apps) contains package acquisition recipes.

Regression inputs and native oracles live in [t/fixtures/core](../../t/fixtures/core).

The JavaScript and polyglot demos have ordinary Cabal targets in
[thc-examples.cabal](thc-examples.cabal). Use `bin/javascript-demo.sh` or
`bin/polyglot-demo.sh` from the repository root with the complete-Core GHC
configuration described in the polyglot guide. The scripts acquire the full
package dependencies before loading either application.

Run the standard-library weak-thread and resurrection example with native GHC:

```sh
cabal run exe:weak-threads --project-dir src/examples
```

After building THC with the pinned toolchain, run the same source through THC:

```sh
cabal run thc -- run exe:weak-threads --project-dir src/examples \
  --thc-root "$PWD" --dist-dir "$PWD/build/weak-threads"
```

The worker returns 42. An independently retained `ThreadId` keeps its weak
identity observable after completion; dropping it lets the observer forget the
worker. A finalizer then returns an ordinary cell to the application, which
updates its value from 41 to 42. Its original weak registration stays dead, and
a fresh registration finalizes after the recovered cell is released. The example
uses only `base`; the [weak-pointer guide](../../docs/weak-explicit.md) describes
the lifetime rules and current platform qualification.

## A Java host owns a Haskell resource

[HostResource.hs](HostResource.hs) allocates an ordinary `ForeignPtr` whose
finalizer frees its buffer and increments an independent cleanup counter.
[HostResourceDemo.java](java/thc/HostResourceDemo.java) keeps the returned
polyglot `Value` in a Java collection across a collection request, reads the
buffer through Haskell's `withForeignPtr`, then clears the Java collection.
The context and cleanup observer stay open while the finalizer runs.

```text
Retained buffer: 100
Released buffer: 1 cleanup
```

From the repository root with the pinned toolchain, run the independent native
reference and the embedding example:

```sh
cabal run exe:host-resource --project-dir src/examples
bin/host-resource-demo.sh
```

The second command uses the existing package acquisition path and JVM example
build. It needs the normal [foreign-code setup](../../docs/interface-foreign.md).
For already acquired Core, invoke the Java example directly through Gradle:

```sh
./gradlew hostResourceDemo --args="@/absolute/path/to/packages.json UNIT:HostResource.session"
```

One application factory returns its operations through the existing logical
tuple/function boundary. Its closures retain only the counter; the resource is
created later and belongs only to Java's `Value` collection. The small `io`
helper supplies the erased `State#` argument and selects the result field from
an ordinary Core IO call. See the [embedding guide](../../docs/site/embedding.md)
for that transport and the current limitation on separately loading entries
whose packages declare overlapping native exports.

Automatic cleanup has no fixed collection count or timing guarantee. Use
explicit close or scoped ownership for scarce resources. The example keeps its
context alive until cleanup is observed; retaining a `Value` cannot make it
usable after that context closes.

Checked with native GHC 9.14.1 and actual Java embedding on the Jam JVM,
bytecode/default. The outputs match, the resource assertions pass, and the
native reference entry passes the authoritative Core audit. No additional
runtime implementation is used by the example.
