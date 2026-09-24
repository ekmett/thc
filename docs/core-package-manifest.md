<!-- SPDX-FileCopyrightText: 2026 Edward Kmett -->
<!-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause -->

# Core packages

When separate GHC units are linked into one THC program, each source component
must be compiled with its actual GHC unit ID. The THC plugin's `unit-qualified`
option writes each module below `units/u-<escaped-unit>/Module.json`; the legacy
flat output layout remains the default for existing single-unit fixtures.
Package modules must use `post-tidy` with GHC code generation. This is the
boundary that matches names in installed `.hi` files: a separately compiled
importer can refer to generated workers absent from the dependency's pre-Tidy
export. The package export also qualifies source file and span IDs by unit, so
two packages can use the same relative source path without merging their text.

The project planner writes one atomic manifest for the selected component
closure, using GHC unit IDs and dependencies from Cabal's plan. Its format is:

```json
{
  "format": "thc-core-packages",
  "schema": 1,
  "ghc": "9.14.1",
  "units": [
    {
      "id": "example-0.1-inplace",
      "depends": ["base-4.22.0.0-1f90"],
      "modules": [
        {
          "name": "Example",
          "boundary": "optimized-Core-after-Tidy-before-CorePrep",
          "path": "core/units/u-example-0.1-inplace/Example.json",
          "sha256": "<lowercase SHA-256 of the complete module file>"
        }
      ]
    }
  ]
}
```

Paths are relative to the manifest and cannot escape its directory. Installed
or native-only dependency units may have no Core modules, but a reachable guest
global must still have an exported definition. Both the static auditor and JVM
loader check module hashes, units, module names, boundaries and binding owners;
they reject missing reachable globals. The auditor accepts
`--package-manifest packages.json`. The JVM command-line module argument accepts
`@packages.json`, including with `--run-io`.

`scripts/test-core-package-link.py` builds an independently registered library
and importer with GHC 9.14.1, compares native output with compiled AST and
bytecode execution, and checks that removing the library fails before execution.
This focused proof uses binder/file provenance from `-g0`; it does not claim
preservation of nested expression SourceNote ticks.
