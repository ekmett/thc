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
