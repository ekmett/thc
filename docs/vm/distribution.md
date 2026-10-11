# Prebuilt runtimes

Download a platform archive from [Jam releases](https://github.com/ekmett/jam/releases).
Each runtime release includes `manifest.json`, `SHA256SUMS`, and a file inventory
for each installation. Pin a release and its archive SHA-256 in your build.
Downloads are public and do not require GitHub Actions credentials.

The GraalVM package contains the matching LabsJDK, patched Graal compiler,
Native Image/SubstrateVM, Jam libraries and Java API. Extract the entire archive
into an empty directory and set `JAVA_HOME` to its `graalvm/` directory. The
plain JDK package uses `jdk/`. Only products listed in a release's manifest are
available: the first release contains GraalVM; older CI runs did not retain
plain JDK archives.

```sh
tar -xf jam-graalvm-<tag>-<flavor>-<platform>-<arch>.tar.gz
export JAVA_HOME="$PWD/graalvm"
export PATH="$JAVA_HOME/bin:$PATH"
java -Xshare:off -Xms256m -Xmx256m \
  -XX:+UnlockExperimentalVMOptions -XX:+UseJamGC -jar application.jar
```

Verify the archive against `SHA256SUMS` before extraction (`sha256sum` on Linux,
`shasum -a 256` on macOS, or `Get-FileHash -Algorithm SHA256` on Windows).
Windows also supports `tar -xf`; set `$env:JAVA_HOME` to the extracted
`graalvm` directory and prepend its `bin` directory to `$env:Path`.

These are preview builds. New archives identify `release` or `fastdebug` in
the filename and manifest; `release` is the optimized build and `fastdebug`
retains VM assertions. The first release used fastdebug and predates the
filename suffix. The `vm-2026.10.09-0e36293` GraalVM release provides
macOS arm64 packages qualified on macOS 15.5, Linux x86_64 packages qualified on
glibc 2.35, and Windows x86_64 packages tested on Windows 11/Server 2022.
Initial packages required macOS 26 or glibc 2.38. Read each release's platform
manifest for its actual requirements; newer compatibility floors do not apply
retroactively to older archives.
Native Image needs the platform C/C++ toolchain, and generated applications may
need companion Jam libraries; see [deployment](native-image.md).

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

The **Publish prebuilt runtimes** workflow takes a successful **Managed runtimes**
run ID, its build flavor and a new release tag. Use a daily, manually dispatched,
or `runtime-validation` run with full qualification; ordinary commit checks do
not produce release packages. Its source must belong to this repository and be
merged into `main`; all fifteen platform/runtime jobs must have passed. It
requires all six JDK/GraalVM archives of that flavor, checks their recorded
`JAM_BUILD_FLAVOR` against the artifact identity, verifies the Actions artifact
hashes, preserves the exact inner archive bytes,
and publishes a prerelease with checksums and provenance. It does not rebuild
a runtime or replace an existing release. CI retains archives for fourteen days,
so promote them before expiration.

To prepare the same assets locally with Python 3.10+ and authenticated `gh`:

```sh
python3 vm/tools/ci/release_runtime.py --run RUN_ID --tag NEW_TAG --build-flavor release \
  --cache /tmp/jam-downloads --output /tmp/jam-release
```

The command only prepares files. The workflow uploads them as a draft, then
publishes after all uploads succeed. A failed upload leaves a draft for inspection.
