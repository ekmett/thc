<!-- SPDX-FileCopyrightText: 2026 Edward Kmett -->
<!-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause -->

`original-stack-clone.json` is a projection of an existing genuine GHC source
export, not handwritten Core or a fresh export. Its provenance records the
original module export SHA-256, pinned GHC source revision, and exporter revision.
The `cloneMyStack1` binding, the two constructors it references, and selected
source metadata are unchanged. The test adds its own explicitly synthetic
consumer and separately moves the original body into that consumer to check
that descriptor dispatch does not depend on the caller's name.

The retained export used GHC 9.14.1 with `-O2 -dcore-lint -g`,
`-this-unit-id ghc-internal`, `-fignore-interface-pragmas`, and plugin options
`post-tidy`, `source-notes`, `closure=cloneMyStack`. No installed GHC executable
or installation tree is hashed. The test does not assert an actual newly
GHC-inlined Backtrace caller: the moved-body case is explicitly synthetic.

The embedded original GHC source and exported body retain the GHC licensing
terms in `compiler/pinned-ghc-internal/LICENSE` (including the University of
Glasgow copyright notice and BSD conditions/disclaimer).

This validates local managed diagnostic capture only. No native GHC stack
layout, IPE decoding, remote capture, AP_STACK, or resumability is claimed.
