# Source locations through optimized Core

THC retains GHC source attribution in both runtime backends. Source and boot exports enable `-g` and structured source notes by default; `THC_SOURCE_NOTES=false` opts out. AST expressions and roots retain immutable source sections; bytecode emission uses the DSL's source-region metadata. There are no executable tick wrappers, logging calls, or debugger events on the normal path.

The exporter records source files with their contents, span identities, original GHC coordinates, and exact UTF-16 character ranges where the contents resolve those coordinates. Expression metadata retains the innermost source and the full outer-to-inner note chain; binder spans provide root fallbacks. Module merging checks consistency when the same source identity occurs more than once. The schema is documented in [the exporter README](../docs/compiler.md#optional-source-attribution).

GHC columns begin at one, expand tabs to eight-column stops, count Unicode code points, and end exclusively. Truffle character ranges count UTF-16 units. `SourceNotes.hs` deliberately includes a supplementary Unicode character, a tab, and a `LINE` pragma naming a missing source file. An independent exporter checker verifies the character ranges; missing or remapped content retains original coordinates without invented offsets. The runtime uses a separate content-free `Source` when no exact character range is available, so GHC tab columns are never mistaken for Java character columns. If an exclusive range ends at column one of a later unavailable line, its primary section identifies the known start point and its provenance still retains the full original range.

Runtime `sourceNotesEnabled=false` suppresses source construction and attachment while evaluating the same exported bundle. Use this switch with the same `-g` corpus and runtime when measuring attachment cost independently of GHC optimization changes.

`SourceNote` is optimization-tolerant attribution. GHC defines it as a non-counting annotation with soft scope; transformations may copy or widen its coverage. It contains a `RealSrcSpan` and a source name. Optimized laziness, inlining, thunk updates and join lowering therefore prevent a promise of one debugger step per original expression. Binder spans locate declarations but do not locate every generated operation. See GHC 9.14.1's [Tickish definition](https://github.com/ghc/ghc/blob/ghc-9.14.1-release/compiler/GHC/Types/Tickish.hs) and [source-position semantics](https://github.com/ghc/ghc/blob/ghc-9.14.1-release/compiler/GHC/Types/SrcLoc.hs).

Both backends expose Graal root and statement tags for debugger attachment.
AST source expressions use standard Truffle wrappers; typed evaluation/forcing
transport does not introduce another statement stop. Bytecode source regions use
the stock DSL tag instrumentation, which requests lazy CBD source replay when a
debugger attaches. Ordinary compact execution and source-disabled execution do
not read debug maps merely to prepare breakpoint support. See the
[driver DAP contract](driver.md#attach-a-debugger) for launch options, embedded
source references and the pinned instrument's early-detach limitation.

Source tables supply attribution for roots, nodes and bytecode locations.
Optimized Core may repeat or collapse source locations. Lexical scopes and
debugger-safe inspection of lazy values remain separate work; stepping does not
promise one event per original Haskell expression or a forced-value history.
