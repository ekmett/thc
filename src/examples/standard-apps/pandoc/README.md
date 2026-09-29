# Pandoc 3.11 application workload

This workload uses the unmodified `pandoc-3.11` and `pandoc-cli-3.11` Hackage
sources with HTTP, Lua, server and REPL support explicitly enabled. The
project keeps Pandoc's default `embed_data_files: False`; its data files are
read from the source package. Cabal must retain the enabled feature flags in
its resolved plan.

The pinned `serialise` and `cborg` releases have upper bounds that exclude
GHC 9.14's boot libraries. The project relaxes only their bounds on `base`,
`containers`, and (for `serialise`) `time`, leaving package sources unchanged.

## Prepare and run natively

Copy this directory into a scratch directory outside the THC source project,
then acquire both packages there with GHC 9.14.1 and Cabal 3.16:

```sh
cabal get pandoc-3.11 pandoc-cli-3.11
cabal build -j2 --enable-build-info pandoc-cli:exe:pandoc
export pandoc_datadir="$PWD/pandoc-3.11"
cabal run pandoc-cli:exe:pandoc -- --version
cabal run pandoc-cli:exe:pandoc -- --help
cabal run pandoc-cli:exe:pandoc -- --from markdown --to html smoke.md
```

The HTML payload must match `expected.html`; Cabal's status messages are not
part of that payload. The data-directory override also applies when invoking
the built executable directly. It points to the package containing `data/`;
an uninstalled executable otherwise searches its configured installation prefix.

## Acquire and run with THC

Use a full-Core GHC 9.14.1 installation and its matching configured GHC source
tree. Acquisition builds the native component and exports its dependency
closure without running Pandoc:

```sh
thc acquire pandoc-cli:exe:pandoc --installed-core required \
  --ghc-source /path/to/ghc-9.14.1 --thc-root /path/to/thc \
  --dist-dir "$PWD/dist-thc"
```

With an installed THC launcher, run the original executable through the same
project and output directory:

```sh
thc run pandoc-cli:exe:pandoc --installed-core required \
  --ghc-source /path/to/ghc-9.14.1 --thc-root /path/to/thc \
  --dist-dir "$PWD/dist-thc" -- --from markdown --to html smoke.md
```

Compare guest output and exit status with the native executable. Check HTTP
resource loading, Lua filters, `pandoc lua` and `pandoc server` through their
actual operations; native compilation, acquisition and `--version` alone do
not establish those guest capabilities. Use `--verify-artifacts` on `thc run`
when an explicit pre-launch Core audit and artifact verification are wanted.

Source archive SHA-256:

- `pandoc-3.11.tar.gz`: `50a04f7e8b244491546c45e81a6e7a0263d7e8c46a776b80738d51294a79f8a8`
- `pandoc-cli-3.11.tar.gz`: `a9f3fc8c64962c8778b379cc2238c7c554541e1e94fd36a210c3f92e1729e929`

Pandoc's own source and license notices remain in its acquired packages;
this repository does not vendor or relicense them.
