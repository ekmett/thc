# Examples

These programs demonstrate the public Haskell runtime APIs:

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
