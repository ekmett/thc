# Application examples

These examples exercise ordinary Haskell applications. Use the
[driver guide](../../../docs/driver.md#run-real-applications) for setup and
Happy, Alex and HsColour commands, including compiling their generated output.
Acquisition builds and exports a program; it does not execute the guest.

| Input or project | Purpose |
| --- | --- |
| `TinyLexer.x` | Alex lexer; its generated program prints `["sum","+","42"]` |
| `TinyParser.y` | Happy parser; its generated program prints `3` |
| `TinyMath.hs` | HsColour HTML input and doctest examples |
| [Word frequency](word-frequency/README.md) | UTF-8 file input, case folding and sorted word counts |
| [Foreign resource lifetime](foreign-resource/README.md) | Native buffers, interior aliases and automatic Haskell finalizers |
| [Buffered Handle lifetime](buffered-handle/README.md) | Automatic buffer flushing, file closure and writer-lock release |
| [lens](lens/README.md) | Public optics operations and original upstream tests |
| [ad](ad/README.md) | Differentiation and sharing-sensitive graph traversal |
| [containers](containers/README.md) | Original IntMap benchmark component |
| [GHC API](ghc-api/README.md) | FastString, compiler-session and typechecking probes |
| [Pandoc](pandoc/README.md) | Document conversion with the configured package features |

Each recipe specifies its dependencies and real native invocation. Compare guest
output and exit status against that invocation. Passing a short example does not
establish complete application or library support. Add `--verify-artifacts` to
`thc run` when a pre-launch dependency audit and artifact verification are wanted.
A failed audit or load is distinct from a failure after guest execution begins.
