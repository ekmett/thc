# Foreign resource lifetime

Own a native buffer with ordinary `Foreign.Concurrent.newForeignPtr`. An interior
`plusForeignPtr` alias keeps the same owner alive, and `withForeignPtr` protects
it throughout the callback, including a collection request and a suspended wait.
The finalizer reads the buffer, evaluates captured lazy Haskell state, frees the
native allocation, and sends its checksum back through an `MVar`.

The first buffer closes with `finalizeForeignPtr`; repeating that call runs no
cleanup again. The second buffer loses its last `ForeignPtr` reference and closes
automatically. Both paths print the same result:

```text
("explicit",129)
("automatic",129)
```

From the THC root with the [pinned toolchain](../../../../README.md), run native
GHC independently:

```sh
cabal run exe:foreign-resource --project-dir src/examples/standard-apps/foreign-resource
```

Then run the same original library Core in THC:

```sh
THC_ROOT="$PWD"
THC_DRIVER=$(cabal list-bin exe:thc)
"$THC_DRIVER" run exe:foreign-resource \
  --project-dir "$THC_ROOT/src/examples/standard-apps/foreign-resource" \
  --thc-root "$THC_ROOT" --dist-dir "$THC_ROOT/build/foreign-resource"
```

The example needs only `base`; the normal installed-Core provider supplies its
library code and native dependencies. See [foreign-code setup](../../../../docs/interface-foreign.md)
for the LLVM tools used during acquisition.

Automatic finalization has no promptness guarantee. This demonstration requests
collections while waiting for its signal; it makes no assumption about their
number. In applications, use deterministic close or `bracket` for scarce resources
and use automatic finalization as a fallback. Never use a pointer after explicit
finalization or let a raw pointer escape its `withForeignPtr` callback.

The example matches native GHC 9.14.1 on Linux in both THC backends and both
handoff modes. Native GHC was also checked on macOS; THC execution on macOS
and this example as a Native Image were not checked.
