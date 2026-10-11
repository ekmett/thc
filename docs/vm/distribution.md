# Runtime packages

THC owns the manual VM producer and release workflows. New packages use the
`thc-graal-*` artifact names and the `thc.vm` / `thc_vm_*` ABI. They contain
LabsJDK, matching Graal/libgraal and SubstrateVM, the Java API, and the complete
native archive closure. There is no standalone JDK distribution.

Existing [Jam releases](https://github.com/ekmett/jam/releases) remain immutable
and available for existing pins. Their old ABI and qualification do not qualify
a THC-branded rebuild. The consumer pin in `etc/jam-graalvm.json` describes
those historical bytes until a newly built provider has passed qualification;
do not rename its paths or edit its hashes to disguise that transition.

New packages must pass the manual VM workflow before publication. Platform
floors belong to each release manifest. No new compatibility claim follows
from moving the source repository.

## Consumer contract

Manifest schema 1 records the producer commit, upstream source pins and passing
CI run. Select a package by `product`, `os`, `arch` and `build_flavor`, then verify
`archive.sha256` before unpacking. `installation.root` is relative to the
extraction directory. Paths in `paths` are relative to that installation,
except `sdk_jars[].path`, which is relative to the extraction directory.
The matching SDK and Truffle JARs retain their existing `upstream/graal25/`
layout alongside `graalvm/`; consumers should use the listed paths and hashes.

`installation.sha256` uses `sha256-path-manifest-v1`: sort all files and symlinks
by slash-separated relative path, without following symlink directories. Hash
the UTF-8 concatenation of `SHA256(content)  path\n` for regular files and
`link  target  path\n` for symlinks. Directories contribute nothing. File modes
are not part of that identity; extraction must preserve executable permissions.
The accompanying inventory lists those entries for diagnosis. The archive hash
also covers the SDK JARs and archive metadata.

The package includes its upstream licenses and notices. Qualification covers
Jam's runtime regression suite, including the Graal and SubstrateVM consumers;
it does not establish arbitrary application compatibility or complete Haskell
`System.Mem.Weak` integration. See [current limits](status.md).

## Publishing

The **Publish prebuilt runtimes** workflow accepts a successful manual VM run,
build flavor and unused release tag. It consumes three platform Graal archives
and verifies the native, shared VM, Graal and Substrate checks. It preserves
archive bytes and records their checksums and producer provenance. It neither
rebuilds a runtime nor replaces an existing release.

```sh
python3 src/vm/tools/ci/release_runtime.py --run RUN_ID --tag NEW_TAG --build-flavor release \
  --cache /tmp/thc-downloads --output /tmp/thc-release
```

This command prepares release files. The workflow uploads a draft and publishes
only after every upload succeeds. Inspect a failed draft before retrying.
