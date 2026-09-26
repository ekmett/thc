# Licensing and reuse provenance

THC is licensed under **UPL-1.0 AND BSD-3-Clause**, following Cadenza.
The rights and obligations of both licenses apply. THC's original contributions
are copyright (c) 2026, Edward Kmett. See [LICENSE](LICENSE) and the complete
terms in [LICENSE.txt](LICENSE.txt). Edward Kmett's copyright notice extends
through 2026; the Oracle notices are unchanged. Third-party components retain
the terms identified below.

## Cadenza runtime adaptation

THC's frame, closure and application runtime is a close adaptation of Edward
Kmett's Cadenza at commit `e2b66e241527cde5d29014af1e4f83d9f2402f88`.

- `src/main/kotlin/thc/runtime/Frames.kt`: indexed frame layout, primitive slot
  specialization and selective final StaticShape capture properties, from
  Cadenza's `jit/frame_layout.kt` and `frame/capture_layout.kt`.
- `src/main/kotlin/thc/runtime/DataValues.kt`: constructor-specific final primitive
  and object fields, following Cadenza's `frame/frame_assembly.kt`, with StaticShape
  construction adapted from `frame/capture_layout.kt`.
- `src/main/kotlin/thc/runtime/Application.kt`: closure/PAP representation,
  generated exact/under/overapplication specializations, bounded direct and
  indirect call caches, bloom-filter tail detection and trampoline, from
  Cadenza's `jit/dispatch.kt`, `jit/tail_calls.kt` and `data/Closure.kt`.
- `src/main/kotlin/thc/runtime/Program.kt`: selective capture construction,
  cached closed closures, shared empty PAP prefix, recursive indirection
  publication, function frame preamble and peeled self-tail loop restoration,
  following Cadenza's lambda, recursive-let and root machinery.
- `src/main/java/thc/runtime/Calls.java`: Java vararg call bridge.
- `src/main/java/thc/runtime/BytecodeRoot.java` and
  `src/main/kotlin/thc/runtime/BytecodeProgram.kt`: Bytecode DSL root, constant
  operation metadata, shared dispatch operations, and replayable lowering, guided
  by Cadenza's `bytecode/BytecodeRoot.java` and `bytecode/compiler.kt`. THC retains
  selective StaticShape captures and adds Core laziness and native self backedges.

Haskell-specific additions are lazy thunk update/blackhole handling, forcing
returned functions before overapplication, GHC Core lowering, and Long rather
than Cadenza's Nat Int capture representation. Cadenza's neutral-value and
normalization paths are intentionally absent.

Cadenza's license terms and Oracle copyright notices are retained in
[LICENSE.txt](LICENSE.txt), with Edward Kmett's copyright extended through 2026.
The original notice is retained without modification in
[third-party-licenses/cadenza-LICENSE.txt](third-party-licenses/cadenza-LICENSE.txt).
The incorporated portions and THC's original modifications use the same SPDX
expression, `UPL-1.0 AND BSD-3-Clause`, including the retained Oracle notices.

The adaptation and Haskell-specific changes are described above; the Cadenza
repository itself was not modified.

## Source headers

Active THC source files carry `SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause`
headers. The Gradle wrapper keeps its own Apache-2.0 identifiers. Captured
benchmark and compiler evidence, including frozen copies of source files, is
preserved byte for byte so recorded hashes remain valid.

The retained GHC sources in `compiler/test-fixtures/empty-join-typeable/` also
remain byte for byte intact. Their adjacent `LICENSE` and provenance identify
the upstream terms; SPDX sidecars identify the BSD-3-Clause source license
without changing the recorded source hashes.

## GHC GMP wrappers

The right-shift and floating-conversion adapters in `src/main/c/gmp-api.c`
adapt GHC 9.14.1's `libraries/ghc-internal/cbits/gmp_wrappers.c`, copyright
(c) 2014 Herbert Valerio Riedel <hvr@gnu.org>, under BSD-3-Clause. The original
terms and University of Glasgow notice are retained in
[`compiler/pinned-ghc-internal/LICENSE`](compiler/pinned-ghc-internal/LICENSE).
Host validation, transport and lifetime management are THC additions.

## Gradle wrapper

`gradlew`, `gradlew.bat`, and `gradle/wrapper/gradle-wrapper.jar` are Gradle
wrapper artifacts, copied from Cadenza. They remain under **Apache-2.0**.
The scripts retain their original copyright and license headers; the JAR
contains its upstream `META-INF/LICENSE`. A full Apache-2.0 text is also
provided in
[third-party-licenses/gradle-LICENSE.txt](third-party-licenses/gradle-LICENSE.txt).
Copying the wrapper through
Cadenza does not change its upstream license.

## Downloaded Haskell sources

The export scripts download third-party Haskell sources into the ignored
`vendor/` cache. These sources are not part of the published THC source tree
and remain under their own upstream terms.

- `compiler/export-map.sh` downloads the upstream containers 0.8 source
  package, including its BSD-style three-clause `LICENSE` and per-file
  copyright notices, into `vendor/containers-0.8/`.
- `compiler/export-boot.py` downloads selected GHC 9.14.1 library sources and
  the complete upstream license collection into `vendor/ghc-9.14.1/`. The
  collection includes the GHC BSD-style license and Haskell report/FFI
  notices. Source-file copyright notices remain intact.

When redistributing those downloaded sources or derived artifacts, retain
those upstream license files and applicable notices.

Generated Core and compiler graph artifacts can contain representations of
upstream library or runtime code. Their underlying third-party portions retain
the applicable upstream terms; generation does not relicense those portions.

Other dependencies are obtained through the build tools and retain their
respective upstream licenses and notices.

## License text sources

The project license terms are copied verbatim from Cadenza's combined
**UPL-1.0 AND BSD-3-Clause** notice. The license identifiers and standard terms
can be checked against the [Oracle UPL text](https://oss.oracle.com/licenses/upl/)
and [SPDX BSD-3-Clause text](https://spdx.org/licenses/BSD-3-Clause.html).
The Gradle wrapper's license text follows the
[Apache Software Foundation's Apache-2.0 text](https://www.apache.org/licenses/LICENSE-2.0.txt).
