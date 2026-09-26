# Build the documentation

From the repository root, using the [supported toolchain](../README.md):

```sh
make docs
make docs-check
```

Install **Pandoc 3.x** for the Markdown guides (or set `PANDOC=/path/to/pandoc`).
The root targets are independent of runtime tests and native/Core fixture
generation:

| Command | Output |
| --- | --- |
| `make docs-haskell` | Compiler `lib:thc` Haddock under `build/docs/haskell/`, public `lib:runtime` Haddock under `build/docs/runtime/` |
| `make docs-jvm` | Mixed Java/Kotlin Dokka under `build/docs/jvm/` |
| `make docs` | Three references, twelve curated guides and one navigable site in `build/site/` |
| `make docs-check` | Recheck the assembled site's links, fragments, assets and revision |

`docs` runs Haddock for the two libraries and Dokka sequentially, with at most two compiler workers.
Use the normal `GHC`, `CABAL`, `CABAL_FLAGS`, `GRADLE_FLAGS`, `JAVA_HOME`, and
`GRADLE_USER_HOME` overrides. Cabal documentation and the small Haskell site tool
use a separate `build/docs/cabal` build tree. A full-Core GHC installation is not
needed to document the exporter library. No installed compiler artifacts are
rebuilt or hashed by this workflow.

Serve `build/site/` with any static HTTP server. Dokka expects HTTP serving for
its search and navigation scripts. `index.html` keeps a persistent left rail;
the selected guide, Haskell API page, or JVM-internals page appears in a titled,
same-origin frame. The shell accepts only HTML paths in the generated page
inventory, including generator search queries and symbol fragments on those
pages. Navigation updates the URL and browser history, so a copied
`?page=api/runtime/THC-Memory.html` link opens the same view. Existing
`api/haskell/` compiler-reference URLs are preserved. The rail's
System/Light/Dark control follows the comonad.com palette in the shell and
content; a direct API URL follows the saved choice or system preference. Direct guide and
API URLs remain usable outside the shell with their native anchors, search,
index, and source links. External source and dependency links open outside the
frame. All site links and assets are relative, so the same output works at the
server root or at the project's `/thc/` Pages path.

## Generators and source identity

The build pins **Dokka 2.2.0**, using its default Gradle v2 integration and K2
analysis. The [official Gradle guide](https://kotlinlang.org/docs/dokka-gradle.html)
documents support for Gradle 7.6+ and Kotlin Gradle plugin 1.9+; the
[2.2.0 release notes](https://github.com/Kotlin/dokka/releases/tag/v2.2.0)
describe its K2 and mixed Java/Kotlin fixes. THC keeps its existing Kotlin 2.4.20
and Gradle 9.7.1 pins. The docs workflow checks that actual combination by
generating the reference; an upstream minimum-version table is not the test.
Dokka uses its embedded analysis language version rather than pretending its
compiler is the project's Kotlin compiler.

Haddock comes from GHC 9.14.1. Use a compiler installation with its dependency
Haddock interfaces for cross-package API links; a custom `--docs=none` GHC can
still generate THC's reference but reports unresolved external type links.
`lib:thc` exposes `THC.Plugin` and `THC.Interface`; its reference is published at
`api/haskell/`. The public `thc:runtime` library is documented separately at
`api/runtime/`: `THC`, `THC.Runtime`, `THC.Thread`, `THC.Memory`, `THC.GC`,
`THC.Trace`, and `THC.Internal.JIT`. The [runtime services guide](runtime-services.md)
explains scope, availability and native-GHC fallbacks. `.Internal` marks an
intentionally unstable interface: `THC.Internal.JIT` exposes version-sensitive
Graal diagnostics and is explicitly `Unsafe` for Safe Haskell.
Driver executables and fixtures are not published as library API.
Missing documentation warnings remain visible; there is no blanket
warnings-as-errors policy for the JVM implementation's public declarations.

The persistent rail shows the source revision and toolchain. Every generated
HTML document retains revision metadata. Dokka and
Haddock declaration source links use the full Git commit ID. The two Haddock
invocations use their actual `compiler/` and `runtime/` source roots; neither
library's links are redirected into the other. Each of the three partial builds
records that ID, and assembly refuses references from a different revision.
Commit edits before producing a publishable site: a local dirty build is useful
for preview but its GitHub links necessarily describe the committed source.

## What gets published

`tools/docs/Main.hs` contains the guide allowlist and assembles the site using
Pandoc and TagSoup. The Markdown files remain their single editable source.
Links to omitted guides, code, benchmark reports and evidence point to that
exact revision in the public repository. There is no recursive `docs/` copy;
large graphs, logs, fixture data and generated DSL dumps stay out of Pages.

The same tool checks every HTML `href` and `src`, local target existence and
fragment IDs, required API/guide pages (including every public runtime module), mixed-language reference presence, shared
navigation and revision. Root-relative and local filesystem URLs fail validation.
It does not make live HTTP requests to external sites or prove JavaScript
behavior in every browser.

The rail owns global navigation. Generator-local search, symbol lists and
indices remain in the content frame. Haddock uses its supported `--theme` CSS
option for typography and color alongside its built-in structural stylesheet,
including quickjump and collapse controls; it does not offer a full HTML shell template.
The assembler adds the shared stylesheet to standalone pages without replacing
generator markup. Assembly preserves Dokka's sidebar HTML fragment. For Haddock
2.33 it adds the
missing local anchors to rendered instance-method declarations and removes an
empty source-line suffix from record-selector file links. All resulting links
still pass the same checker; missing targets are not exempted.

`.github/workflows/docs.yml` builds a cached, docs-only Pages artifact from
`main`. An active publication finishes while a newer run may replace pending
work; frequent main pushes do not continually cancel the active site build. Deployment uses the
GitHub Pages artifact/environment mechanism; no `gh-pages` source branch is
created. Repository Pages settings must use **GitHub Actions** as the source.
