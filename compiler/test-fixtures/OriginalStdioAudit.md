# Original GHC stdio write fixture

`OriginalStdioAudit.hs` imports unchanged installed GHC 9.14.1
`GHC.Internal.System.Posix.Internals.c_write` / `c_safe_write` and
`GHC.Internal.Foreign.C.Error.getErrno`. It has no foreign import declarations.
Each scalar consumer takes `(Int# fd, Addr# base, Int# byteOffset, Word# count)`
and returns `Int#` through one immediate `runRW#` State lambda. This is a small
test boundary, not a public pure write API or a replacement Handle algorithm.

Prepare using the existing Haskell `thc-fixtures` executable under the shared
build-directory gate with pinned GHC and Cabal:

```sh
scripts/prepare-original-stdio.sh --require-supported
```

The fixture supports native Linux/macOS LP64 only. Kotlin checks the exact original
static targets, units, capi/ccall conventions, safety, and declared/actual
representations. A different platform wrapper numbering or ABI fails closed;
there is no wildcard target recognition. Pre- and post-Tidy exports each retain
six original FCall applications: two unsafe writes, two safe writes, and two
get-errno calls. The target copies live in the consumers, not just a supplied
unused library module. `--require-supported` invokes the shared Core auditor and
requires all four roots to pass at both stages; preparation alone never claims
JVM support. No stdio-specific Python preparer or semantic model is involved.

`build/original-stdio/manifest.json` records commands, source/artifact hashes,
toolchain metadata and paths to requested strict audits. Installed GHC
artifacts are not hashed. `pre/core` and `post/core` contain genuine exporter
outputs. Kotlin derives all six applications' actual and declared raw proofs from
that Core and checks that each root has one reachable top-level binding and two
guest lambda calls, including the immediate State lambda. The producer does not
serialize a second proof inventory or repeat the Kotlin expectations.

`oracle.json` records 144 native observations, compared with an independently
derived Kotlin model. Payload bytes cover all values 0 through 255;
stdout/stderr remain binary and are captured independently. Each process writes
its numeric result to a separate file under `results`, never into either tested
stream. Per-invocation bytes, commands and exit statuses are retained in `logs`.

The two write roots report the original signed count or -1. The two errno roots
immediately observe original `getErrno` on failure; Kotlin compares it with the
host ABI's `EBADF`, not a private error category. On success they return `-(count+2)`
without asserting the unspecified successful-write errno. Both streams, invalid
signed-32-bit descriptors, offsets, one-past-end zero-length buffers, NUL/newline
and high bytes are covered. All native pointer accesses are defined. Malformed
addresses/counts, partial channel writes, EINTR/readiness, and embedding IO
policy belong in separate managed-runtime tests. This fixture is not evidence
of complete original GHC Handle or putStrLn execution.
