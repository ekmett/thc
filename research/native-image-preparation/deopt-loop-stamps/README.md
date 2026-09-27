# Unchanged loop-local stamps in deoptimization parsers

This optional, pinned builder overlay investigates a Native Image parser defect.
It is not an application-runtime patch or proof of native guest JIT/AOT. The
normal pure-image recipe is unchanged.

`SharedBytecodeParser` forces loop phis for deoptimization targets. The inherited
`stampFromValueForForcedPhis` hook defaults to false, so an unchanged word-array
local loses its array type at the loop header. Word-operation parsing then cannot
recognize a word-array store. The patch enables the existing stamp hook only for
deoptimization targets that are not OSR graphs, retaining the superclass result
otherwise.

`FrameStateBuilder.insertLoopPhis` already limits that hook to locals which
`LocalLiveness.localIsChangedInLoop` marks unchanged. Changed references and
numeric locals retain unrestricted joins; operand-stack and monitor stamps are
also unchanged. All forced phis and deoptimization proxies remain present. The
word/object compatibility checks are neither removed nor relaxed.

## Reproduction

Select the exact GraalVM 25.3.4.1 distribution with `JAVA_HOME`. Preparation checks
SHA-256 identities of its `svm.src.zip` and `svm.jar`, extracts one original source
file, applies the patch, and recompiles only its class family. A different archive
fails closed and needs a separate source audit, not a hash-only update.

```sh
bash research/native-image-preparation/deopt-loop-stamps/prepare.sh build/native-image/deopt-loop-stamps
bash research/native-image-preparation/deopt-loop-stamps/check.sh \
  build/native-image/deopt-loop-stamps/checks \
  build/native-image/deopt-loop-stamps/thc-svm-deopt-loop-stamps.jar
THC_NATIVE_IMAGE_DEOPT_LOOP_STAMPS=1 \
  bash research/native-image-preparation/prepared-image.sh "$PWD" build
```

Use the normal build-directory lease and shared image-build gate on a shared
host. The research recipe rebuilds and checks the overlay before using
`-J--patch-module=org.graalvm.nativeimage.builder=...`. It does not overwrite the
installed toolchain, dependency cache, or distribution JARs. Unset the opt-in to
use the unmodified builder. `prepare-only` still only prepares the initialization
inventory, not the builder overlay.

The overlay contains only the recompiled `SharedGraphBuilderPhase` class family,
its complete modified source with original notices, and the toolchain's supplied
license text. The original source header specifies GPL-2.0-only with the
Classpath exception; its notices remain intact. The standalone scripts and
regression tools carry their own repository license headers. The output directory
retains scratch sources/classes and `provenance.sha256`; JAR entry timestamps are
fixed for reproducibility.

## Controls and limits

`LoopStampTest.java` uses the pinned compiler APIs and liveness computed from a
real fixture method. It contrasts the stock false hook with the candidate true
hook, checking unchanged word-array and numeric locals, changed reference and
counter locals, stack/monitor values, retained forced phis and distinct
deoptimization proxy chains. The stock graph reproduces lost word-array type;
the candidate passes the existing word-array store plugin. Word-to-object-array
and object-to-word-array stores must still bail out.

`OverlayHookTest.java` checks the exact overlay payload and interprets the small
emitted Boolean hook for every D/OSR/superclass combination. It rejects unexpected
instructions rather than loading hosted SVM classes in an ordinary application
JVM. These are compiler-API and classfile checks, not machine-code deoptimization
or native-image execution. A production-entry image build and subsequent explicit
guest compilation/immediate installed calls are separate mandatory controls.
