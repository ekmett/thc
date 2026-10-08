# Buffered Handle lifetime

Ordinary `System.IO` handles keep a file open and own its output buffers. This
example writes a short message with block buffering, then requests collection
while the handle is still live. Trying to open a reader must fail with
`AlreadyInUse`: the writer still holds GHC's file lock.

One handle is closed explicitly with `hClose`; its file can immediately be read.
A second handle leaves scope without flushing or closing. Its original Haskell
finalizer flushes the buffer and closes the file. The example requests collections
until a reader can open it, then checks the exact message. Reopening proves that
the writer lock was released; reading the complete message proves that the buffer
was flushed. These locks are local to the Haskell program, not OS advisory locks.

From the THC root with the [pinned toolchain](../../../../README.md), create a
scratch directory and run native GHC independently:

```sh
SCRATCH=$(mktemp -d)
cabal run exe:buffered-handle --project-dir src/examples/standard-apps/buffered-handle -- "$SCRATCH"
```

Run the same original library code through THC:

```sh
THC_ROOT="$PWD"
THC_DRIVER=$(cabal list-bin exe:thc)
"$THC_DRIVER" run exe:buffered-handle \
  --project-dir "$THC_ROOT/src/examples/standard-apps/buffered-handle" \
  --thc-root "$THC_ROOT" --dist-dir "$THC_ROOT/build/buffered-handle" -- "$SCRATCH"
```

Both paths should print:

```text
explicit: flushed and closed
automatic: flushed and closed
```

The directory must exist. Each run writes `explicit.txt` and `automatic.txt`
inside it, replacing files with those names. Both contain `buffered resource
finalized` followed by a newline; they remain available for inspection. The
example needs only `base` and the normal [foreign-code setup](../../../../docs/interface-foreign.md).

Automatic cleanup has no deadline, so the example makes no assumption about the
number of collections. Use `withBinaryFile` or `bracket` in applications to close
scarce resources deterministically and observe any close error directly.

The example matches native GHC 9.14.1 on Linux with bytecode/default and AST/dense
execution. Strict runtime AST admission of the complete reachable Core also
passed without executing guest bodies. Native GHC was checked on macOS; THC
on macOS and this example as a Native Image were not checked.

The optional Python prelaunch audit currently rejects the original interruptible
open declaration when package-native metadata is present. This is a known audit
limitation: the runtime selects its existing file-ownership operation for that
call. The ordinary run command above retains runtime ABI and ownership checks.
