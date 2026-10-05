# Word frequency

Count words across UTF-8 files using ordinary ByteString file IO, Text case
folding, and a strict Map. The output is sorted by word, with a count and tab
before each word. Whitespace separates words; punctuation remains part of a word.
Each file is read into memory, and the accumulated word table stays in memory.

From the THC repository root with the [pinned toolchain](../../../../README.md),
build the independent native application:

```sh
cabal build exe:word-frequency --project-dir src/examples/standard-apps/word-frequency
WORD_FREQUENCY=$(cabal list-bin exe:word-frequency --project-dir src/examples/standard-apps/word-frequency)
"$WORD_FREQUENCY" src/examples/standard-apps/word-frequency/sample.txt
```

The sample prints:

```text
2	hello
2	strasse
2	λ
```

Additional filenames contribute to the same counts. Empty files contribute no
words. Missing arguments, unreadable files, and invalid UTF-8 report an error on
stderr and exit unsuccessfully without printing a partial table.

Run the same application through THC after `make` in the repository root:

```sh
THC_ROOT="$PWD"
THC_DRIVER=$(cabal list-bin exe:thc)
"$THC_DRIVER" run exe:word-frequency \
  --project-dir "$THC_ROOT/src/examples/standard-apps/word-frequency" \
  --thc-root "$THC_ROOT" --dist-dir "$THC_ROOT/build/word-frequency" -- \
  "$THC_ROOT/src/examples/standard-apps/word-frequency/sample.txt"
```

The default pinned provider acquires the libraries on the first run. Their native
code also needs the [LLVM tools](../../../../docs/interface-foreign.md#run-a-package-with-native-imports)
on `PATH`, or the documented `THC_*` tool selections. On macOS with Homebrew
LLVM 21, use `export PATH="$(brew --prefix llvm@21)/bin:$PATH"`.
This example uses only the GHC-pinned base, bytestring, text, and containers
packages; it adds no native dependencies of its own.

The pinned-provider application has been compared with native GHC on macOS
arm64 in both AST and bytecode backends and both handoff modes. Multi-file Unicode
counts match; empty input, missing files, invalid UTF-8 and usage exits were also
checked. These checks do not establish large-input performance. Running this
application from a complete retained-Core installation with `--installed-core
required` remains to be qualified in [#1059](https://github.com/ekmett/thc/issues/1059).
