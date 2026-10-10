# Nix and OCI

The Linux x86-64 development environment uses the same pinned Nixpkgs and GHC
9.14.1 as Hide. It supplies Cabal 3.16, LLVM 18 and the Jam GraalVM distribution
selected by `etc/jam-graalvm.json`.

```sh
nix develop
# Or include the separately packaged Hide editor:
nix develop .#with-hide
```

These shells provide compiler development environments. Build THC from the
checkout using [the normal workflow](contributing.md), or build the installed
package below for ordinary projects.

The Jam archive is fetched by its existing release URL and SHA256. Its binaries
are not patched for Nix: THC checks the complete supplier installation digest.
`buildFHSEnv` supplies the ordinary Linux library layout around those bytes.
The interactive environment therefore requires Linux user namespaces; running it
inside a container that prohibits nested namespaces fails before tool execution.

The Hide input is pinned independently to its qualified Nix packaging revision.
Updating it requires checking its local Cabal packages as well as the shared
Nixpkgs/GHC selection. Hide continues to discover THC through `PATH` or its
configured command.

## Package components

The native compiler tools and JVM distribution can also be built separately:

```sh
nix build .#native-tools
nix build .#jvm
```

`native-tools` builds the Cabal components with GHC 9.14.1, shared libraries and
retained simplified Core for THC's modules. It preserves Cabal's actual package
registrations. `jvm` builds the Gradle distribution using the pinned GHC source
for runtime C support and a hashed Maven dependency closure. Its build runs
inside the toolchain filesystem through `proot`, leaving the Jam installation
unchanged.

These components feed the installed package:

```sh
nix build .#thc
# From an ordinary Cabal project, with build output owned by that project:
/path/to/result/bin/thc run
# Or select the package through Nix:
nix run .#thc -- run --project-dir /path/to/project
```

The installed package retains Cabal's genuine final-prefix plugin and runtime
registrations, merges the actual dependency registrations into a private package
database, and exports the exact runtime unit's retained Core to CBD. Construction
runs after native stripping and RPATH fixup. Native dependency roots come from
Nix's dependency closure; package identities and library paths are preserved.
The descriptor, tools, audit resources and JVM distribution belong to the same
immutable prefix. This is a fixed-prefix installation; copying it to another
location is not supported.

The `thc` wrapper selects the pinned compiler, unchanged Jam and toolchain Linux
filesystem through `proot`, without requiring user namespaces. It exposes the
current directory, home and temporary directory to the tools. Run from the
project or keep project/output paths within those directories. The project owns
Cabal build output and THC's caller cache; the installed prefix stays read-only.
Boot library Core is acquired into that cache according to the selected
`--installed-core` policy. The separate `jvm` output still requires Jam and the
Linux library layout supplied by this wrapper.

After a deliberate Gradle dependency change, regenerate its lock and verify an
ordinary build from the result:

```sh
nix build .#jvm.mitmCache.updateScript -o update-jvm-deps
./update-jvm-deps
nix build .#jvm
```

The dependency recorder needs network access. Ordinary builds consume the
recorded artifact hashes. The artifact workflow below currently builds the
development image, not these compiler components.

## Container

The development image contains the same toolchain filesystem. It runs as UID/GID
1000 and does not need a nested FHS wrapper or elevated container privileges.
Its linker cache is created during the image build so the root filesystem can
remain read-only.

```sh
nix build .#development-image
docker load < result
mkdir -p .container-home
docker run --rm -it --read-only \
  --tmpfs /tmp:rw,nosuid,nodev \
  --mount "type=bind,src=$PWD,dst=/work" \
  --mount "type=bind,src=$PWD/.container-home,dst=/home/thc" \
  thc-development:nix
```

The mounted project and home must be writable by UID/GID 1000. On a host with a
different user ID, pass `--user "$(id -u):$(id -g)"`. The project owns its build
outputs; the mounted home owns Cabal and Gradle caches. Removing the container
does not remove either mount.

This is a development image, not a prebuilt THC release. It has no registry
publication step. The packaging workflow produces an archive independently of
ordinary commit tests; its checks exercise the packaged tools with an unprivileged
user and read-only system directories. Building or running the development
image does not qualify THC's installed compiler or Native Image execution.
