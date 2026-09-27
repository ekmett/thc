# Runtime snippet-template ownership

This optional, hash-pinned builder overlay repairs the replacement-cache owner
used by runtime graph parsing. It does not filter options, expose hosted-only
classes to the image, or establish guest-JIT/AOT support.

`NativeImageGenerator.registerReplacements` already uses `RuntimeOptionValues`
for runtime lowerings and constructs separate runtime snippet templates.
`RuntimeCompilationFeature.initializeAnalysisProviders`, however, starts with
the original-method analysis providers and replaces the constant-field provider
and graph-builder plugins without replacing their hosted replacement cache.
`SnippetSubstitutionInvocationPlugin` reads that cache through its parser context
and retains the selected template as `SnippetSubstitutionNode` data. A runtime
graph can consequently retain a hosted template and its hosted-only option keys.

The patch adds only `copyWith(hostedProviders.getReplacements())` to that existing
provider-copy chain, before the unchanged plugin assignment. The field-provider
wrapper and all other analysis providers remain unchanged. Runtime snippets keep
their complete existing runtime option set; original compilation keeps its
original providers and hosted options. There is no global object replacement or
option-descriptor mutation.

## Reproduction

Use the exact inspected GraalVM 25.3.4.1 distribution as `JAVA_HOME`. The source
and builder archive SHA-256 checks fail closed on a different distribution.

```sh
bash research/native-image-preparation/runtime-snippet-providers/prepare.sh \
  build/native-image/runtime-snippet-providers
bash research/native-image-preparation/runtime-snippet-providers/check.sh \
  build/native-image/runtime-snippet-providers/checks \
  build/native-image/runtime-snippet-providers/thc-svm-runtime-snippet-providers.jar
THC_NATIVE_IMAGE_DEOPT_LOOP_STAMPS=1 THC_NATIVE_IMAGE_RUNTIME_SNIPPETS=1 \
  bash research/native-image-preparation/prepared-image.sh "$PWD" build
```

Apply the existing host build/capture gates. The two optional overlays are
independent source changes combined into one builder `--patch-module` argument.
The deoptimization-parser overlay is a prerequisite for probing past the earlier
word-array parser failure, not a dependency of this overlay's focused API checks.
The public pure-interpreter recipe, installed runtime JARs, shared JDK and Maven
cache remain untouched. `prepare-only` still prepares only the image inventory.

The output includes a deterministic overlay, complete modified source retaining
its original copyright/license header, the supplied toolchain license text,
scratch classes, and a provenance manifest. The source header specifies
GPL-2.0-only with the Classpath exception. One source file produces the feature's
class family and its two existing package-private helper classes; the test checks
the exact payload.

## Focused controls

`SnippetProvidersTest.java` exercises the pinned compiler/API boundary directly:
the actual StringLatin1 template constructors and generic snippet-substitution
plugin, with small replacement-registry/parser-context test doubles. It contrasts
the stock and candidate provider setup, inspects which template the real node
retains, and checks option and provider identity. The hosted fixture uses the
actual `NativeImageOptions.NumberOfThreads` key; runtime compiler option overrides
remain intact. Unexpected test-double calls fail rather than silently succeeding.

The Java 25 ClassFile check verifies the emitted setup's ordered field-wrapper,
replacement-cache and plugin calls without initializing the hosted feature on a
plain JVM. These are causal compiler-API and bytecode controls, not a full parser,
image build, machine-code deoptimization, or guest execution test. A matching
production-entry image and immediate installed-call checks remain separate gates.
