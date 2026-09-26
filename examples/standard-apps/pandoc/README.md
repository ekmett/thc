# Pandoc 3.11 application workload

This workload uses the unmodified `pandoc-3.11` and `pandoc-cli-3.11` Hackage
sources. The project explicitly disables Lua, server and REPL support; it is
not evidence for those optional features. The resolved Pandoc library flags are
also `-http -embed_data_files`. Native execution is a baseline, not THC guest
execution.

Copy this directory into a scratch directory outside the THC source project,
then acquire both packages there with the selected Cabal 3.16 installation:

```sh
cabal get pandoc-3.11 pandoc-cli-3.11
cabal build -j4 --enable-build-info pandoc-cli:exe:pandoc
cabal run pandoc-cli:exe:pandoc -- --version
cabal run pandoc-cli:exe:pandoc -- --help
cabal run pandoc-cli:exe:pandoc -- --from markdown --to html smoke.md
```

The HTML payload must match `expected.html`; Cabal's own status messages are
not part of that payload. When invoking the built executable directly rather
than using `cabal run`, set `pandoc_datadir` to the absolute unpacked
`pandoc-3.11` directory. It contains the original `data/` files. Without the
override an uninstalled binary looks in its configured installation prefix
and conversion exits 97 on the missing `data/abbreviations` file.

With the pinned full-Core GHC 9.14.1 installation, an installed THC launcher,
and the corresponding configured GHC source tree, the guest attempt is:

```sh
export pandoc_datadir="$PWD/pandoc-3.11"
thc run . --exe pandoc-cli:exe:pandoc --installed-core required \
  --ghc-source /path/to/ghc-9.14.1 --thc-root /path/to/thc \
  --dist-dir "$PWD/dist-thc" -- --version
```

Replace the suffix after `--` with `--help` or
`--from markdown --to html smoke.md`. The driver uses the original complete
Core and strict dependency audit. Do not replace the guest invocation with
the native binary, remove unsupported branches from the application, or label
successful preparation as guest success. Use bytecode for original executable
startup; the AST backend still has a separate signal-startup limitation.

Native Linux x86_64 GHC 9.14.1 baseline: version and help succeed; the tiny
conversion matches the expected HTML with the data-directory override. The
first runtime-enablement slice is the original POSIX environment family used
by Pandoc's user-data-directory lookup, including the string encoder's
`realloc`. Its genuine original `System.Environment` fixture passes native
comparison, strict full-Core audit and both backend/handoff-mode checks.
Full Pandoc guest execution remains under investigation; this directory does
not claim a passing THC application.

Source archive SHA-256:

- `pandoc-3.11.tar.gz`: `50a04f7e8b244491546c45e81a6e7a0263d7e8c46a776b80738d51294a79f8a8`
- `pandoc-cli-3.11.tar.gz`: `a9f3fc8c64962c8778b379cc2238c7c554541e1e94fd36a210c3f92e1729e929`

Pandoc's own source and license notices remain in its acquired packages;
this repository does not vendor or relicense them.
