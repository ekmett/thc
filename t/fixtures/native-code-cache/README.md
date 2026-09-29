# Native code-cache fixtures

These inputs exercise scalar calls, recursion, lazy data, numeric carriers,
higher-order functions, typed transport, heap fields, bytes and Text. They are
regression workloads, not public API or application examples.

`OPAQUE`/`NOINLINE` preserve the guest call roots, partial applications and shared
CAFs under test. The typed and reference-join inputs also keep their documented
GHC optimization controls so those boundaries survive Core export. Keep these
constraints when changing a fixture; removing them can remove the tested path.

Use the [cache guide](../../../docs/native-code-cache.md) for build/store/run commands.
