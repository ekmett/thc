# Lazy file preview

Ordinary `readFile` returns a lazy `String`. Its unread tail keeps the original
Handle alive so later demands can continue reading, even after the function
that opened the file has returned. Dropping that tail allows automatic Handle
cleanup without demanding the remaining input.

This example previews one record, requests a collection, then reads a second
record through the same deferred input. While the tail is live, a conflicting
writer cannot open the file. After the preview returns without demanding the
remaining record, the example waits for cleanup and appends a new record.
There is no explicit input Handle or `hClose` in the preview.

The sample prints:

```text
Preview: first record
After collection: second record
Discarded tail: writer reopened.
```

From the THC root with the [pinned toolchain](../../../../README.md), run native
GHC independently in a caller-owned scratch directory:

```sh
NATIVE_SCRATCH=$(mktemp -d)
cabal run exe:lazy-file --project-dir src/examples/standard-apps/lazy-file -- "$NATIVE_SCRATCH"
```

Run the same application through THC with a separate scratch directory:

```sh
THC_ROOT="$PWD"
THC_DRIVER=$(cabal list-bin exe:thc)
THC_SCRATCH=$(mktemp -d)
"$THC_DRIVER" run exe:lazy-file \
  --project-dir "$THC_ROOT/src/examples/standard-apps/lazy-file" \
  --thc-root "$THC_ROOT" --dist-dir "$THC_ROOT/build/lazy-file" -- "$THC_SCRATCH"
```

Inspect the preserved unread record and the later append:

```sh
cat "$THC_SCRATCH/records.txt"
```

```text
first record
second record
unread record
appended after preview
```

The program creates or overwrites `records.txt` in the supplied directory.
It needs only `base` and the normal [foreign-code setup](../../../../docs/interface-foreign.md).
The reader/writer conflict comes from GHC's file bookkeeping, not an operating
system advisory lock. Collection timing is unspecified; the cleanup wait requests
collections with a brief delay and assumes no fixed number. Use strict input or
scoped streaming when resource closure must occur at a known point.

The example matches native GHC 9.14.1 on Linux with bytecode/default and
AST/dense execution, including the resulting file. The prelaunch artifact audit
accepts the application using the same runtime as the AST run.
