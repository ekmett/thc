# Runtime graph simulated-field eligibility

Both Native Image recipes apply this correction to the pinned JAM builder. It changes builder field folding; THC's runtime classes are unchanged.
The runtime graph encoder converts `ImageHeapConstant` values into runtime constants
by unwrapping their hosted objects. Class-initializer simulation can instead produce
references with no hosted object. Those values are useful for ordinary image
construction but cannot pass through this encoder conversion.

The analysis-time change is in `InlineBeforeAnalysisGraphDecoderImpl.handleLoadFieldNode`.
After the existing initializer processing and simulated-field canonicalization,
it declines a candidate only when the root graph is `RUNTIME_COMPILED_METHOD`,
the candidate is an `ImageHeapConstant`, and its hosted object is absent. The
unchanged field-interception path and original field load remain the fallback.
It does not initialize extra classes, reconstruct hosted objects, or suppress an
encoder error. Primitive, null and host-backed constants remain eligible. Original
and deoptimization graph behavior, simulation state, and initialization nodes
are unchanged.

The second change covers late runtime-graph optimization, whose dedicated
`RuntimeCompilationReflectionProvider.readFieldValue` can also return simulated
field values. It performs the original `readValue` call once, with the same
field, receiver and flags, then returns unknown only for a hostless
`ImageHeapConstant`. All other results and exceptions are preserved. Declining
that constant leaves the runtime field load available instead of introducing an
object the runtime graph encoder cannot encode. This does not change the analysis
reflection provider or the ordinary/deoptimization compilation providers.

```sh
JAVA_HOME=/path/to/pinned-graalvm bash research/native-image-preparation/runtime-simulated-folds/prepare.sh build/simulated-folds
JAVA_HOME=/path/to/pinned-graalvm bash research/native-image-preparation/runtime-simulated-folds/check.sh build/simulated-folds/checks build/simulated-folds/thc-svm-runtime-simulated-folds.jar
bash research/native-image-preparation/prepared-image.sh "$PWD" build
```

Use the existing host resource leases. The preparation pins both builder source
and binary hashes, preserves upstream source/license notices, and emits a separate
two-class module overlay. The enclosing `RuntimeCompiledMethodSupport` and all
its other nested classes stay pinned and unmodified. The overlay never modifies
an installed JDK or dependency cache.
The correction composes with the other experimental overlays. Its focused checks run separately from image preparation.

`SimulatedFoldTest.java` inspects the actual javac output through the pinned JDK
ClassFile API and executes its small branch sequence with strict compiler-service
markers. The real method-variant predicate receives original, deoptimization,
runtime and non-variant method proxies. Controls cover constant eligibility,
unchanged candidate/fallback identity, initializer and interception call ordering,
and the exact overlay payload. Separate emitted-provider controls cover hostless
instance and array rejection, hosted/primitive/null result identity, the single
original read and its flags, and exception propagation. Java is used at this javac/ClassFile/JVMCI boundary,
consistent with the existing overlay checks; this is not a new runtime helper.
The marker controls do not execute the whole image analysis or prove native guest
compilation. Retain the actual production-entry build outcome and subsequent
first-installed-call checks separately. Guest JIT and guest AOT remain distinct.
