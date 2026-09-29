# Build and publish the documentation

Use the [supported GHC and GraalVM toolchain](../README.md) and install
**Pandoc 3.x**. From the repository root:

```sh
make docs
make docs-check
```

The site is written to `build/site/`. Serve that directory with a static HTTP
server to preview the guides and API references together. The same output works
at a server root or under the project's `/thc/` GitHub Pages path.

| Command | Output |
| --- | --- |
| `make docs-haskell` | Compiler and public runtime Haddock in `build/docs/haskell/` and `build/docs/runtime/` |
| `make docs-jvm` | Java implementation reference in `build/docs/jvm/` |
| `make docs` | API references and selected Markdown guides assembled into `build/site/` |
| `make docs-check` | Check the assembled site's links, anchors, assets and source revision |

Set `PANDOC=/path/to/pandoc` if needed. The build also accepts the normal
`GHC`, `CABAL`, `CABAL_FLAGS`, `GRADLE_FLAGS`, `JAVA_HOME` and `GRADLE_USER_HOME`
overrides. Documentation uses a separate Cabal build tree under
`build/docs/cabal/` and does not require runtime tests or Core fixtures.
A complete-Core GHC installation is not needed. Install GHC's dependency
Haddock interfaces if you want cross-package API links; without them, Haddock
reports unresolved external links.

## Edit and add documentation

Edit the Markdown in `docs/`; the front page and shared appearance live in
`docs/site/`. Document public Haskell APIs with Haddock and Java declarations
with Javadoc. Keep examples and limitations consistent with current code.
See [contributing](contributing.md) for the development workflow.

To publish a new guide, add it to the `guides` list in
[`src/tools/docs/Main.hs`](../src/tools/docs/Main.hs). Pages includes only those
guides and the generated references. Links to other tracked repository files
point to GitHub at the site's source revision; graph archives and logs are not
copied into the site. Use relative links and run `make docs-check` after changes.

The shared navigation provides appearance controls and links to the guides,
Haskell libraries and Java reference. API search, symbol anchors and source
links remain available. Copy the browser URL to link directly to a selected
page or symbol.

## Publish from main

Commit changes before building a publishable site. All references must be built
from the same Git revision, and their source links identify that revision. If
assembly reports mismatched revisions, rebuild with `make docs`. A dirty local
preview is useful for editing, but its GitHub source links still describe the
committed source.

The documentation workflow publishes `main` through GitHub Actions. In the
repository's Pages settings, select **GitHub Actions** as the source. A passing
`make docs-check` verifies local links and assets; it does not test external
websites or every browser's JavaScript behavior.
