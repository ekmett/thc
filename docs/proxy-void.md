# GHC's zero-width proxy constant

GHC 9.14.1 defines `proxy# :: forall {k} (a :: k). Proxy# a` as a wired-in
identifier. `Proxy#` has kind `TYPE (TupleRep '[])`, so its value occupies no
physical registers. THC recognizes GHC's exact `proxyHashKey` and exports the
existing certified scalar `void` node at both Core boundaries. It supplies no
external Haskell body or runtime stub.

This supports original code such as containers' `Data.Map.Internal.$wbogus`,
whose `(# #) -> Proxy# ()` worker returns `proxy#`. The empty tuple input and
scalar void result remain distinct logical shapes, even though both have zero
physical width. Calls that produce `Proxy#` still execute and can throw.

Run `cabal run exe:thc-fixtures --offline -- proxy-void` to generate native
results, exact GHC identity controls and strictly audited pre/post-Tidy Core.
`ProxyVoidTest` compares both backends with the native results, checks the first
compiled call with and without inlining, retains a throwing producer, and rejects
replacement of a proxy field with an empty tuple. The ordinary `testDefault` and
`testDense` tasks run these checks in both handoff modes. Existing bundle receipts
remain immutable; the exporter change applies to newly exported Core.
