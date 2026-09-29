# Explicit JSON consumer controls

`package-consumer.json` and `interface-closure.json` are handwritten runtime
controls. The consumer applies `dependency:Hidden.cold` to its argument; the
independent expected result is the argument plus three. The fragment keeps the
original binding owner and declares the complete `synthetic:LazyJson` support
module separately. `CoreJsonConsumersTest` checks module order, ownership and
execution through both backends.
