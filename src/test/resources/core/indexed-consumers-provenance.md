# Explicit JSON consumer controls

`indexed-consumer.json` and `indexed-interface-closure.json` are hand-written
runtime controls, not GHC exports. The consumer applies the interface fragment's
`dependency:Hidden.cold` to its argument; the independent expected result is
the argument plus three. The fragment deliberately keeps the original binding
owner and declares the complete `synthetic:LazyJson` support module separately.

`CoreJsonConsumersTest` pins both JSON hashes and builds navigation from those
bytes. Serialized `.idx` sidecars have been retired. The original JSON fixtures
and their independent expected results are unchanged.
