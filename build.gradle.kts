// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.security.MessageDigest
import java.net.URI

plugins {
    application
    kotlin("jvm") version "2.4.20"
    kotlin("kapt") version "2.4.20"
    id("org.jetbrains.dokka") version "2.2.0"
}
repositories { mavenCentral() }

// Documentation reads handwritten sources; it does not compile the runtime,
// generate Truffle DSL classes, or prepare native/Core fixtures.
val docsRevision = providers.gradleProperty("thc.docsRevision").orElse(
    providers.exec { commandLine("git", "rev-parse", "HEAD") }.standardOutput.asText.map { it.trim() })
dokka {
    moduleName.set("THC")
    moduleVersion.set(docsRevision.map { it.take(12) })
    dokkaPublications.html {
        outputDirectory.set(layout.buildDirectory.dir("docs/jvm"))
        includes.from("docs/site/jvm.md")
        failOnWarning.set(false)
        suppressInheritedMembers.set(true)
    }
    dokkaSourceSets.named("main") {
        includes.from("docs/site/jvm.md")
        sourceRoots.setFrom("src/main/kotlin", "src/main/java")
        classpath.setFrom(configurations.compileClasspath)
        jdkVersion.set(25)
        reportUndocumented.set(false)
        suppressGeneratedFiles.set(true)
        sourceLink {
            localDirectory.set(file("src/main"))
            remoteUrl.set(docsRevision.map { URI("https://github.com/ekmett/thc/blob/$it/src/main") })
            remoteLineSuffix.set("#L")
        }
    }
}
// The checked family table generates concrete primitive carriers and typed nodes.
// BytecodeRoot's DSL requires nested declarations; its marked regions are checked,
// never rewritten by a build. Refresh them explicitly with the generator --write.
val generateSimdFamilies = tasks.register<Exec>("generateSimdFamilies") {
    inputs.files("scripts/generate-simd-families.py", "scripts/simd-families.json",
        "src/main/java/thc/runtime/BytecodeRoot.java", "src/main/kotlin/thc/runtime/BytecodeProgram.kt")
    outputs.dir(layout.buildDirectory.dir("generated/simd"))
    commandLine("python3", "scripts/generate-simd-families.py", "--check")
}
kotlin.sourceSets.main { kotlin.srcDir(layout.buildDirectory.dir("generated/simd/kotlin")) }
tasks.matching { it.name in setOf("compileKotlin", "compileJava", "kaptGenerateStubsKotlin") }.configureEach {
    dependsOn(generateSimdFamilies)
}
val graalVersion = "25.3.4.1"
// Additional languages are opt-in; the ordinary runtime stays language-neutral.
val polyglotDemoRuntime by configurations.creating
dependencies {
    implementation(kotlin("stdlib"))
    implementation("org.graalvm.polyglot:polyglot:$graalVersion")
    implementation("org.graalvm.truffle:truffle-api:$graalVersion")
    runtimeOnly("org.graalvm.truffle:truffle-runtime:$graalVersion")
    runtimeOnly("org.graalvm.polyglot:llvm-community:$graalVersion")
    kapt("org.graalvm.truffle:truffle-dsl-processor:$graalVersion")
    testAnnotationProcessor("org.graalvm.truffle:truffle-dsl-processor:$graalVersion")
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    polyglotDemoRuntime("org.graalvm.polyglot:js:$graalVersion")
}
kotlin {
    jvmToolchain(25)
    // Kotlin's generated null-check failures inline stack-trace sanitization into
    // Truffle partial evaluation. Guest/host validation remains explicit.
    compilerOptions {
        freeCompilerArgs.addAll("-Xno-param-assertions", "-Xno-call-assertions", "-Xno-receiver-assertions")
    }
}
// Tests and benchmark controls can opt out explicitly; ordinary launches use compact headers.
val compactObjectHeaders = providers.gradleProperty("thc.compactObjectHeaders").orElse("true").get()
require(compactObjectHeaders == "true" || compactObjectHeaders == "false") { "thc.compactObjectHeaders must be true or false" }
val compactHeaderOption = "-XX:${if (compactObjectHeaders == "true") "+" else "-"}UseCompactObjectHeaders"
application {
    mainClass.set("thc.MainKt")
    applicationDefaultJvmArgs = listOf("--add-modules=jdk.incubator.vector", "--enable-native-access=ALL-UNNAMED", "-Xss2m", compactHeaderOption)
}
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Exported Core and native expectations are test inputs even when JVM sources
    // are unchanged. Source inputs also make stale corpus fingerprints observable.
    inputs.files(fileTree(layout.buildDirectory) {
        include("core/**/*.json", "source-core/**/*.json", "cbv-post-core/**/*.json",
            "floating/core/**/*.json", "floating/checks.json", "floating/oracle.tsv",
            "word-floating/**/*.json", "word-floating/oracle.tsv",
            "fused-floating/**/*.json", "fused-floating/oracle.tsv",
            "floating-tuple/**/*.json", "floating-tuple/*.tsv", "floating-tuple/native/**",
            "tuple-input/**/*.json", "tuple-input/*.tsv", "tuple-input/native/**",
            "io-main-pap/**/*.json", "io-main-pap/*.tsv", "io-main-pap/native/**",
            "addr-identity/**/*.json", "addr-identity/oracle.txt", "addr-identity/native",
            "empty-join-input/**/*.json", "empty-join-input/*.tsv", "empty-join-input/native/**",
            "empty-tuple-input/**/*.json", "empty-tuple-input/*.tsv", "empty-tuple-input/native/**",
            "sqrt/**/*.json", "sqrt/*.tsv", "sqrt/native/**",
            "tag-to-enum/**/*.json", "tag-to-enum/*.tsv", "tag-to-enum/native/**",
            "show-int/**/*.json", "show-int/*.tsv", "show-int/native/**",
            "narrow-literal-proofs/**/*.json", "narrow-literal-proofs/*.tsv", "narrow-literal-proofs/native/**",
            "bignat-literals/**/*.json", "bignat-literals/*.tsv", "bignat-literals/native/**",
            "show-word-list/**/*.json", "show-word-list/*.tsv", "show-word-list/native/**",
            "short-bytes-slices/**/*.json", "short-bytes-slices/*.tsv", "short-bytes-slices/native/**",
            "unsafe-equality/**/*.json", "unsafe-equality/*.tsv", "unsafe-equality/native/**", "unsafe-equality/api/**",
            "aggregate-core/**/*.json", "aggregate-post-core/**/*.json", "map/boot-core/**/*.json",
            "aggregate-layout/pre-core/**/*.json", "aggregate-layout/post-core/**/*.json",
            "sum-layout/**/*.json", "sum-layout/*.tsv", "sum-layout/native/**",
            "sum-result/**/*.json", "sum-result/*.tsv", "sum-result/native/**",
            "tuple-return/pre-core/**/*.json", "tuple-return/post-core/**/*.json", "tuple-return/oracle.tsv",
            "state-tuple/pre-core/**/*.json", "state-tuple/post-core/**/*.json", "state-tuple/oracle.tsv",
            "state-tuple/provenance.json", "state-tuple/*-audit.json", "state-tuple/native/**",
            "tuple-join/pre-core/**/*.json", "tuple-join/post-core/**/*.json", "tuple-join/oracle.tsv",
            "tuple-arithmetic/pre-core/**/*.json", "tuple-arithmetic/post-core/**/*.json",
            "tuple-arithmetic/manifest.json", "tuple-arithmetic/oracle.tsv", "tuple-arithmetic/call-oracle.tsv",
            "integer-primops/core/**/*.json", "integer-primops/manifest.json", "integer-primops/oracle.tsv",
            "mutvar/**/*.json", "mutvar/oracle.tsv", "mutvar/NativeMutVar.hs",
            "stable-pointers/**/*.json", "stable-pointers/oracle.tsv", "stable-pointers/NativeStablePointer.hs",
            "weak-explicit/**/*.json", "weak-explicit/oracle.tsv", "weak-explicit/NativeWeak.hs",
            "shrink-bytearrays/**/*.json", "shrink-bytearrays/oracle.tsv", "shrink-bytearrays/NativeShrinkByteArrays.hs",
            "fetch-add-int-array/**/*.json", "fetch-add-int-array/oracle.tsv", "fetch-add-int-array/NativeFetchAddIntArray.hs",
            "managed-mvars/**/*.json", "managed-mvars/*.tsv", "managed-mvars/native/**",
            "synchronous-exceptions/**/*.json", "synchronous-exceptions/*.tsv", "synchronous-exceptions/native/**",
            "core-continuation/**/*.json", "core-continuation/native-output.txt",
            "thread-status/**/*.json", "thread-status/oracle.txt",
            "uncaught-self/**/*.json", "uncaught-self/native/oracle",
            "mask-functions/**/*.json", "mask-functions/logs/*.stdout", "mask-functions/logs/*.stderr",
            "mask-functions/native/oracle",
            "interface-core/**/*.json", "interface-core/logs/*.stdout", "interface-core/logs/*.stderr",
            "interface-core/**/*.hi", "interface-core/**/*.dyn_hi", "interface-core/native/oracle",
            "interface-core/**/*.zip",
            "interface-core/source/*.saved",
            "original-stdio/**/*.json", "original-stdio/results/*.txt", "original-stdio/native/**",
            "original-stdio/logs/*.stdout", "original-stdio/logs/*.stderr",
            "original-stdio-read/**/*.json", "original-stdio-read/results/*.txt", "original-stdio-read/native/**",
            "original-stdio-read/logs/*.stdout", "original-stdio-read/logs/*.stderr",
            "original-stdio-read/input.bin",
            "original-handle-readiness/**/*.json", "original-handle-readiness/native/**",
            "original-handle-readiness/logs/*.stdout", "original-handle-readiness/logs/*.stderr",
            "original-posix-stat/**/*.json", "original-posix-stat/native/oracle",
            "original-posix-stat/logs/*.stdout", "original-posix-stat/logs/*.stderr",
            "libdw-unavailable/manifest.json", "libdw-unavailable/oracle.json", "libdw-unavailable/foreign-labels.json",
            "native-addresses/manifest.json", "native-addresses/oracle.json",
            "native-malloc/manifest.json", "native-malloc/oracle.txt",
            "original-gmp/**/*.json", "original-gmp/native/oracle", "original-gmp/exposed-ghc-internal.conf",
            "original-gmp/logs/*.stdout", "original-gmp/logs/*.stderr",
            "original-stdio-close/**/*.json", "original-stdio-close/results/*.txt", "original-stdio-close/results/*.private",
            "original-stdio-close/native/**", "original-stdio-close/logs/*.stdout", "original-stdio-close/logs/*.stderr",
            "original-posix-dup/**/*.json", "original-posix-dup/results/*.txt", "original-posix-dup/results/*.private",
            "original-posix-dup/results/*.other", "original-posix-dup/native/oracle",
            "original-posix-dup/logs/*.stdout", "original-posix-dup/logs/*.stderr",
            "original-stdio-seek/**/*.json", "original-stdio-seek/results/*.txt", "original-stdio-seek/results/*.private",
            "original-stdio-seek/native/**", "original-stdio-seek/logs/*.stdout", "original-stdio-seek/logs/*.stderr",
            "original-stdio-truncate/**/*.json", "original-stdio-truncate/results/*.txt", "original-stdio-truncate/results/*.private",
            "original-stdio-truncate/native/**", "original-stdio-truncate/logs/*.stdout", "original-stdio-truncate/logs/*.stderr",
            "original-fd-ready/**/*.json", "original-fd-ready/native/oracle", "original-fd-ready/native/private-file",
            "original-rts-locks/**/*.json", "original-rts-locks/logs/*.stdout", "original-rts-locks/logs/*.stderr",
            "original-open/**/*.json", "original-open/logs/*.stdout", "original-open/logs/*.stderr", "original-open/native/oracle",
            "original-termios/**/*.json", "original-termios/logs/*.stdout", "original-termios/logs/*.stderr", "original-termios/native/oracle",
            "original-tcsetattr/**/*.json", "original-tcsetattr/logs/*.stdout", "original-tcsetattr/logs/*.stderr", "original-tcsetattr/native/oracle",
            "original-sigprocmask/**/*.json", "original-sigprocmask/logs/*.stdout", "original-sigprocmask/logs/*.stderr", "original-sigprocmask/native/oracle",
            "original-tcgetattr/**/*.json", "original-tcgetattr/logs/*.stdout", "original-tcgetattr/logs/*.stderr", "original-tcgetattr/native/oracle",
            "original-sigset/**/*.json", "original-sigset/logs/*.stdout", "original-sigset/logs/*.stderr", "original-sigset/native/oracle",
            "original-termios/saved/native/oracle",
            "original-fd-ready/logs/*.stdout", "original-fd-ready/logs/*.stderr",
            "original-iconv/**/*.json", "original-iconv/native/oracle",
            "original-iconv/logs/*.stdout", "original-iconv/logs/*.stderr",
            "managed-md5-native/**",
            "original-stack/manifest.json", "original-stack/run-*/**",
            "original-stack-formatter/manifest.json", "original-stack-formatter/run-*/logs/*",
            "original-stack-formatter/run-*/pre-core/*.json", "original-stack-formatter/run-*/post-core/*.json",
            "original-stack-formatter/run-*/*-audit.json", "original-stack-formatter/run-*/native/formatter",
            "original-stack-formatter/run-*/originals/core/*.json",
            "original-stack-formatter/run-*/originals/generated/**/*.hs",
            "original-stack-formatter/run-*/originals/generated.json",
            "original-stack-formatter/run-*/originals/target-layout.json",
            "pinned-addresses/**/*.json", "pinned-addresses/*.tsv", "pinned-addresses/native/**",
            "pinned-pointer-cells/**/*.json", "pinned-pointer-cells/*.tsv", "pinned-pointer-cells/native/**",
            "wide-char-address/**/*.json", "wide-char-address/logs/*.stdout", "wide-char-address/native/**",
            "floating-address/**/*.json", "floating-address/*.tsv", "floating-address/native/**",
            "explicit64-arrays/**/*.json", "explicit64-arrays/*.tsv", "explicit64-arrays/native/**",
            "managed-address-reads/**/*.json", "managed-address-reads/*.tsv", "managed-address-reads/native/**",
            "scalar-bitcasts/**/*.json", "scalar-bitcasts/*.tsv", "scalar-bitcasts/NativeScalarBitCast.hs", "scalar-bitcasts/native/**",
            "compare-byte-arrays/**/*.json", "compare-byte-arrays/*.tsv", "compare-byte-arrays/NativeCompareByteArrays.hs", "compare-byte-arrays/native/**",
            "bytearray/**/*.json", "bytearray/oracle.tsv", "bytearray/NativeByteArray.hs",
            "mutable-bytearray-size/**/*.json", "mutable-bytearray-size/*.tsv", "mutable-bytearray-size/native/**",
            "resize-bytearrays/**/*.json", "resize-bytearrays/*.tsv", "resize-bytearrays/native/**",
            "mutable-bytearrays/**/*.json", "mutable-bytearrays/*.tsv", "mutable-bytearrays/NativeMutableByteArrays.hs", "mutable-bytearrays/native/**",
            "array-slices/**/*.json", "array-slices/*.tsv", "array-slices/NativeArraySlices.hs", "array-slices/native/**",
            "boxed-arrays/**/*.json", "boxed-arrays/*.tsv", "boxed-arrays/NativeBoxedArray.hs", "boxed-arrays/native/**",
            "small-arrays/**/*.json", "small-arrays/*.tsv", "small-arrays/native/**",
            "boxed-array-extensions/manifest.json", "boxed-array-extensions/run-*/**",
            "address-fields/**/*.json", "address-fields/*.tsv", "address-fields/NativeAddressFields.hs", "address-fields/native/**",
            "data-to-tag/**/*.json", "data-to-tag/*.tsv", "data-to-tag/NativeDataToTag.hs", "data-to-tag/native/**",
            "int-arrays/**/*.json", "int-arrays/oracle.tsv", "int-arrays/expected.tsv",
            "int-arrays/NativeIntArray.hs", "int-arrays/native/int-array-oracle",
            "double-arrays/**/*.json", "double-arrays/oracle.tsv", "double-arrays/expected.tsv",
            "double-arrays/NativeDoubleArray.hs", "double-arrays/native/double-array-oracle",
            "int32-arrays/**/*.json", "int32-arrays/oracle.tsv", "int32-arrays/expected.tsv",
            "int32-arrays/literal-oracle.tsv",
            "int32-arrays/NativeInt32Array.hs", "int32-arrays/native/int32-array-oracle",
            "float-word-arrays/**/*.json", "float-word-arrays/oracle.tsv", "float-word-arrays/expected.tsv",
            "float-word-arrays/NativeFloatWordArray.hs", "float-word-arrays/native/float-word-array-oracle",
            "int8-arrays/**/*.json", "int8-arrays/*.tsv", "int8-arrays/NativeInt8Array.hs", "int8-arrays/native/**",
            "int16-arrays/**/*.json", "int16-arrays/oracle.tsv", "int16-arrays/expected.tsv", "int16-arrays/literal-oracle.tsv",
            "int16-arrays/NativeInt16Array.hs", "int16-arrays/native/int16-array-oracle",
            "bit-primops/**/*.json", "bit-primops/oracle.tsv", "bit-primops/NativeBitPrimops.hs",
            "simd/pre-core/**/*.json", "simd/post-core/**/*.json", "simd/oracle.tsv",
            "simd-families/**/*.json", "simd-families/*.tsv", "simd-families/native/**",
            "simd-capability-smoke/**/*.json", "simd-capability-smoke/*.tsv", "simd-capability-smoke/native/**", "generated/simd/fixtures/*.hs",
            "explicit64-primops/core/**/*.json", "explicit64-primops/manifest.json", "explicit64-primops/oracle.tsv",
            "simd-int32x4/pre-core/**/*.json", "simd-int32x4/post-core/**/*.json", "simd-int32x4/oracle.tsv",
            "simd-floatx4/**/*.json", "simd-floatx4/*.tsv",
            "simd-floatx4-fma/**/*.json", "simd-floatx4-fma/oracle.txt",
            "simd-doublex2/**/*.json", "simd-doublex2/*.tsv",
            "simd-int16x8/**/*.json", "simd-int16x8/*.tsv", "simd-int16x8/native/int16x8-oracle",
            "simd-int8x16/**/*.json", "simd-int8x16/*.tsv", "simd-int8x16/native/int8x16-oracle",
            "simd-word8x16/**/*.json", "simd-word8x16/*.tsv", "simd-word8x16/native/word8x16-oracle",
            "simd-word16x8/**/*.json", "simd-word16x8/*.tsv", "simd-word16x8/native/word16x8-oracle",
            "simd-word32x4/**/*.json", "simd-word32x4/*.tsv", "simd-word32x4/native/word32x4-oracle",
            "simd-int32x4-multiply/**/*.json", "simd-int32x4-multiply/*.tsv", "simd-int32x4-multiply/native/int32x4-multiply-oracle",
            "simd-int32x4-bytearray/**/*.json", "simd-int32x4-bytearray/*.tsv", "simd-int32x4-bytearray/native/int32x4-bytearray-oracle",
            "simd-word32x4-bytearray/**/*.json", "simd-word32x4-bytearray/*.tsv", "simd-word32x4-bytearray/native/word32x4-bytearray-oracle",
            "simd-floatx4-bytearray/**/*.json", "simd-floatx4-bytearray/*.tsv", "simd-floatx4-bytearray/native/floatx4-bytearray-oracle",
            "simd-doublex2-bytearray/**/*.json", "simd-doublex2-bytearray/*.tsv", "simd-doublex2-bytearray/native/doublex2-bytearray-oracle",
            "signed-narrow-primops/core/**/*.json", "signed-narrow-primops/manifest.json", "signed-narrow-primops/oracle.tsv",
            "corpus/**/*.json", "corpus/oracle.tsv", "native/oracle.tsv")
    })
    inputs.files(fileTree("examples") { include("**/*.hs", "coverage.json") })
    inputs.files(fileTree("compiler") { include("**/*.hs", "*.sh", "*.py") })
    inputs.file("compiler/test-fixtures/OriginalStackProof.json")
    inputs.files("src/THC/Driver/Wired.hs", "compiler/target-layout.c")
    inputs.files(fileTree("compiler/pinned-ghc-internal") {
        include("**/*.hs", "**/*.hs-boot", "**/*.hsc", "include/WordSize.h", "LICENSE")
    })
    inputs.files("thc.cabal", "compiler/pinned-ghc-internal/LICENSE",
        "compiler/pinned-ghc-internal/GHC/Internal/InfoProv/Types.hsc",
        "compiler/pinned-ghc-internal/GHC/Internal/Heap/InfoTable.hsc")
    inputs.files(fileTree("test/haskell-fixtures") { include("**/*.hs") })
    inputs.file("thc.cabal")
    inputs.files(fileTree("vendor/ghc-9.14.1") { include("**/*.hs", "**/*.hs-boot", "LICENSE") })
    inputs.files(fileTree("scripts") {
        include("simd-families.json", "generate-simd-families.py", "prepare-simd-families.py",
            "prepare-simd-capability-smoke.py", "simd_family_model.py", "test-simd-families.py")
        include("prepare-corpus.py", "prepare-floating-audit.py", "prepare-floating-tuples.py", "prepare-scalar-bitcasts.py", "scalar_bitcast_model.py", "test-scalar-bitcasts.py", "prepare-tag-to-enum-audit.py", "prepare-unsafe-equality-audit.py", "prepare-show-int.py", "show_int_model.py", "test-show-int-model.py", "prepare-narrow-literal-proofs.py", "test-narrow-literal-proofs.py", "prepare-bignat-literals.py", "bignat_literal_model.py", "test-bignat-literals.py", "prepare-show-word-list.py", "show_word_list_model.py", "test-show-word-list-model.py", "prepare-short-bytes-slices.py", "short_bytes_slice_model.py", "test-short-bytes-slices-model.py", "test-core-enums.py", "prepare-bytearray.py", "prepare-mutable-bytearray-size.py", "prepare-resize-bytearrays.py", "prepare-mutable-bytearrays.py", "mutable_bytearray_model.py", "test-mutable-bytearray-model.py", "prepare-compare-byte-arrays.py", "prepare-boxed-arrays.py", "prepare-array-slices.py", "test-array-slice-model.py",
            "prepare-simd-audit.py", "prepare-floatx4-audit.py", "prepare-doublex2-audit.py", "doublex2_model.py", "test-doublex2-model.py", "core_vectors.py",
            "prepare-state-tuple-audit.py", "prepare-empty-tuple-input-audit.py", "prepare-tuple-input-audit.py", "prepare-io-main-pap.py", "test-tuple-inputs.py", "prepare-empty-join-input.py", "test-empty-join-inputs.py", "core_*.py", "generate-scalar-signatures.py",
            "prepare-managed-mvars.py", "test-managed-mvar-fixtures.py", "test-managed-mvars.py",
            "prepare-synchronous-exceptions.py", "test-synchronous-exception-fixtures.py",
            "prepare-managed-md5.py", "prepare-pinned-addresses.py",
            "prepare-int16x8-audit.py", "int16x8_model.py", "test-int16x8-model.py",
            "prepare-address-fields.py", "test-address-fields.py", "prepare-address-identity.sh",
            "prepare-managed-address-reads.py",
            "prepare-data-to-tag.py", "test-core-data-tags.py",
            "prepare-int8x16-audit.py", "int8x16_model.py", "test-int8x16-model.py",
            "prepare-word8x16-audit.py", "word8x16_model.py", "test-word8x16-model.py",
            "prepare-word16x8-audit.py", "word16x8_model.py", "test-word16x8-model.py",
            "prepare-word32x4-audit.py", "word32x4_model.py", "test-word32x4-model.py",
            "prepare-int32x4-multiply-audit.py", "int32x4_multiply_model.py", "test-int32x4-multiply-model.py",
            "prepare-int32x4-bytearray-audit.py", "int32x4_bytearray_model.py", "test-int32x4-bytearray-model.py", "test-core-vector-memory.py",
            "prepare-word32x4-bytearray-audit.py", "word32x4_bytearray_model.py", "test-word32x4-bytearray-model.py", "test-core-word32-vector-memory.py",
            "prepare-floatx4-bytearray-audit.py", "floatx4_bytearray_model.py", "test-floatx4-bytearray-model.py", "test-core-float-vector-memory.py",
            "prepare-doublex2-bytearray-audit.py", "doublex2_bytearray_model.py", "test-doublex2-bytearray-model.py", "test-core-double-vector-memory.py",
            "audit-core.py", "core-capabilities.json", "check-corpus-structure.py",
            "check-sum-layout.py", "sum_layout_model.py", "test-sum-layout.py", "prepare-sum-result-audit.py", "test-core-sums.py")
    })
    jvmArgs(application.applicationDefaultJvmArgs)
    systemProperty("thc.projectRoot", projectDir.absolutePath)
    // Keep the default tests independent of THC_BACKEND; bytecode tests select their backend explicitly.
    systemProperty("thc.backend", "ast")
    testLogging { events("failed", "skipped", "passed") }
}
tasks.test {
    useJUnitPlatform { excludeTags("jit-stability", "simd-families-experiment") }
}
// Actual private boot-library FCallId proofs require installed full Core. Keep
// this named suite independent of stock/thin default fixture groups; selecting
// it without preparing its real fixture is an error, never a skipped test.
val fullCoreTests = sourceSets.create("fullCoreTest")
configurations[fullCoreTests.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[fullCoreTests.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())
fullCoreTests.compileClasspath += sourceSets.test.get().output
fullCoreTests.runtimeClasspath += sourceSets.test.get().output
kotlin.target.compilations.getByName("fullCoreTest").associateWith(kotlin.target.compilations.getByName("main"))
kotlin.target.compilations.getByName("fullCoreTest").associateWith(kotlin.target.compilations.getByName("test"))
tasks.register<Test>("originalIconvFullCoreTest") {
    group = "verification"
    description = "Tests original locale/iconv imports using the explicitly prepared full-Core GHC fixture."
    testClassesDirs = fullCoreTests.output.classesDirs
    classpath = fullCoreTests.runtimeClasspath
    useJUnitPlatform()
    filter { includeTestsMatching("thc.runtime.OriginalIconvNativeTest") }
    outputs.upToDateWhen { false }
    outputs.doNotCacheIf("Full-Core native/compiled evidence requires a fresh test process") { true }
    doFirst {
        check(file("build/original-iconv/manifest.json").isFile) {
            "Missing original-iconv fixture: select a full-Core GHC9.14.1 and run cabal run exe:thc-fixtures -- original-iconv (docs/original-iconv.md)"
        }
    }
}
tasks.register<Test>("boundThreadQueryFullCoreTest") {
    group = "verification"
    description = "Tests the original negative bound-thread capability against both native RTS modes."
    maxHeapSize = "4g"
    testClassesDirs = fullCoreTests.output.classesDirs
    classpath = fullCoreTests.runtimeClasspath
    useJUnitPlatform()
    filter { includeTestsMatching("thc.runtime.BoundThreadQueryNativeTest") }
    outputs.upToDateWhen { false }
    outputs.doNotCacheIf("Original-import first-installed evidence requires a fresh test process") { true }
    doFirst {
        check(file("build/bound-thread-query/manifest.json").isFile) {
            "Missing bound-thread-query fixture: run cabal run exe:thc-fixtures -- bound-thread-query with full-Core GHC9.14.1"
        }
    }
}
tasks.register<Test>("fileWaitFullCoreTest") {
    group = "verification"
    description = "Tests original GHC descriptor waits using explicitly prepared Linux full Core."
    maxHeapSize = "4g"
    testClassesDirs = fullCoreTests.output.classesDirs
    classpath = fullCoreTests.runtimeClasspath
    useJUnitPlatform()
    filter { includeTestsMatching("thc.runtime.FileWaitFullCoreTest") }
    outputs.upToDateWhen { false }
    outputs.doNotCacheIf("Original descriptor-wait evidence requires a fresh test process") { true }
    doFirst {
        check(file("build/file-wait/manifest.json").isFile) {
            "Missing file-wait fixture: use selected complete-Core GHC9.14.1 on Linux and run cabal run exe:thc-fixtures -- file-wait"
        }
    }
}
tasks.register<Test>("originalStackDecoderFullCoreTest") {
    group = "verification"
    description = "Tests the original stack decoder/formatter using the explicitly prepared full-Core GHC fixture."
    testClassesDirs = fullCoreTests.output.classesDirs
    classpath = fullCoreTests.runtimeClasspath
    inputs.files(fileTree("build/original-stack-decoder") {
        include("**/*.json", "installed/bundles/*.zip", "logs/*.stdout", "logs/*.stderr", "native/oracle")
    })
    useJUnitPlatform()
    filter { includeTestsMatching("thc.runtime.OriginalStackDecoderTest") }
    outputs.upToDateWhen { false }
    outputs.doNotCacheIf("Full-Core native/compiled evidence requires a fresh test process") { true }
    doFirst {
        check(file("build/original-stack-decoder/manifest.json").isFile) {
            "Missing original-stack-decoder fixture: select a full-Core GHC9.14.1 and run cabal run exe:thc-fixtures -- original-stack-decoder"
        }
    }
}
tasks.register<Test>("arithmeticExceptionsFullCoreTest") {
    group = "verification"
    description = "Tests original arithmetic exception payloads using the explicitly prepared full-Core GHC fixture."
    testClassesDirs = fullCoreTests.output.classesDirs
    classpath = fullCoreTests.runtimeClasspath
    inputs.files(fileTree("build/arithmetic-exceptions") {
        include("**/*.json", "installed/bundles/*.zip", "logs/*.stdout", "logs/*.stderr", "native/oracle")
    })
    useJUnitPlatform()
    filter { includeTestsMatching("thc.runtime.ArithmeticExceptionsNativeTest") }
    outputs.upToDateWhen { false }
    outputs.doNotCacheIf("Full-Core native/compiled evidence requires a fresh test process") { true }
    doFirst {
        check(file("build/arithmetic-exceptions/manifest.json").isFile) {
            "Missing arithmetic-exceptions fixture: select a full-Core GHC9.14.1 and run cabal run exe:thc-fixtures -- arithmetic-exceptions"
        }
    }
}
tasks.register<Test>("simdFamiliesExperimentTest") {
    group = "verification"
    description = "Runs the prepared generated SIMD Core, native-oracle, and compiled-path experiment."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("simd-families-experiment") }
    outputs.upToDateWhen { false }
    outputs.doNotCacheIf("SIMD evidence must be checked in a fresh test process") { true }
}
// Keep optional language tests outside the ordinary test inventory and classpath.
val polyglotTests = sourceSets.create("polyglotTest")
configurations[polyglotTests.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[polyglotTests.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())
kotlin.target.compilations.getByName("polyglotTest").associateWith(kotlin.target.compilations.getByName("main"))
tasks.register<Test>("polyglotTest") {
    group = "verification"
    description = "Tests THC's optional interop boundary against GraalJS."
    testClassesDirs = polyglotTests.output.classesDirs
    classpath = polyglotTests.runtimeClasspath + polyglotDemoRuntime
}
tasks.register<Test>("jitStabilityTest") {
    group = "verification"
    description = "Runs advisory JIT code-retention checks; failures remain visible to local callers."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("jit-stability") }
    outputs.upToDateWhen { false }
    outputs.doNotCacheIf("JIT stability must be measured in a fresh test process") { true }
}
tasks.register<JavaExec>("probe") {
    group = "verification"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("thc.ProbeKt")
    jvmArgs(application.applicationDefaultJvmArgs)
    workingDir(projectDir)
}

tasks.register<JavaExec>("polyglotDemo") {
    group = "application"
    description = "Runs exported Haskell against GraalJS through THC.Polyglot."
    classpath = sourceSets.main.get().runtimeClasspath + polyglotDemoRuntime
    mainClass.set("thc.PolyglotDemoKt")
    jvmArgs(application.applicationDefaultJvmArgs)
    workingDir(projectDir)
}

// Vector intrinsics are isolated in Java; floating vectors retain fixed species.
tasks.withType<JavaCompile>().configureEach { options.compilerArgs.addAll(listOf("--add-modules", "jdk.incubator.vector")) }

// Compile the unchanged pinned GHC cbits for this host. The resulting bitcode
// remains an optional execution path; no native pointer is exposed to Core.
val compileCbits by tasks.registering(Exec::class) {
    inputs.files("scripts/build-cbits.py", "src/main/c/md5-api.c", "src/main/c/iconv-api.c",
        "src/main/c/strerror-locale.c", "src/main/c/libdw-unavailable.c",
        "compiler/pinned-ghc-internal/cbits/strerror.c",
        "src/main/c/gmp-api.c",
        "bench/experiments/pinned-addresses/reference/md5.c",
        "bench/experiments/pinned-addresses/reference/md5.h")
    inputs.files(fileTree("compiler/pinned-ghc-rts") { include("*.c", "*.h") })
    outputs.dir(layout.buildDirectory.dir("generated/cbits"))
    outputs.upToDateWhen { false }
    commandLine("python3", "scripts/build-cbits.py", "--output", layout.buildDirectory.dir("generated/cbits").get().asFile)
}
sourceSets.main { resources.srcDir(layout.buildDirectory.dir("generated/cbits")) }
tasks.processResources { dependsOn(compileCbits) }

// Optional Linux opened-resource provider; not original fstat admission.
val compileNativeFiles by tasks.registering {
    dependsOn("generateStdioAbi")
    val source = layout.projectDirectory.file("src/main/c/native-file-api.c")
    val stdio = layout.buildDirectory.file("generated/stdio-abi/thc/native/stdio-host-abi.json")
    val output = layout.buildDirectory.dir("generated/native-files")
    val clang = providers.environmentVariable("THC_CLANG").orElse("clang")
    inputs.file(source); inputs.file(stdio); inputs.property("clang", clang)
    outputs.dir(output)
    doLast {
        val host = JsonSlurper().parse(stdio.get().asFile) as Map<*, *>
        if (host["system"] == "Linux" && host["architecture"] == "x86_64") {
            val destination = output.get().asFile.resolve("thc/native/native-file-api.so")
            destination.parentFile.mkdirs()
            providers.exec { commandLine(clang.get(), "--target=${host["target"]}", "-std=c11", "-Wall", "-Wextra", "-Werror", "-O2",
                "-fPIC", "-fembed-bitcode", "-shared", source.asFile.path, "-o", destination.path) }.result.get()
        }
    }
}
sourceSets.main { resources.srcDir(layout.buildDirectory.dir("generated/native-files")) }
tasks.processResources { dependsOn(compileNativeFiles) }

// Partial original sigprocmask, loaded only with explicit native authority.
val compileNativeSignals by tasks.registering {
    dependsOn("generateStdioAbi")
    val source = layout.projectDirectory.file("src/main/c/native-signal-api.c")
    val stdio = layout.buildDirectory.file("generated/stdio-abi/thc/native/stdio-host-abi.json")
    val output = layout.buildDirectory.dir("generated/native-signals")
    val clang = providers.environmentVariable("THC_CLANG").orElse("clang")
    inputs.file(source); inputs.file(stdio); inputs.property("clang", clang)
    outputs.dir(output)
    doLast {
        val host = JsonSlurper().parse(stdio.get().asFile) as Map<*, *>
        if (host["system"] == "Linux" && host["architecture"] == "x86_64") {
            val destination = output.get().asFile.resolve("thc/native/native-signal-api.so")
            destination.parentFile.mkdirs()
            providers.exec { commandLine(clang.get(), "--target=${host["target"]}", "-std=c11", "-Wall", "-Wextra", "-Werror", "-O2",
                "-fPIC", "-fembed-bitcode", "-shared", source.asFile.path, "-o", destination.path) }.result.get()
        }
    }
}
sourceSets.main { resources.srcDir(layout.buildDirectory.dir("generated/native-signals")) }
tasks.processResources { dependsOn(compileNativeSignals) }

// Original stdio FCalls use target C widths/errno, not JVM or private-ABI values.
val generateStdioAbi by tasks.registering {
    val source = layout.projectDirectory.file("src/main/c/stdio-abi-probe.c").asFile
    val output = layout.buildDirectory.dir("generated/stdio-abi")
    val clang = providers.environmentVariable("THC_CLANG").orElse("clang")
    inputs.file(source)
    inputs.property("clang", clang)
    outputs.dir(output)
    outputs.upToDateWhen { false }
    doLast {
        fun run(command: List<String>): String = providers.exec { commandLine(command) }.standardOutput.asText.get()
        fun architecture(value: String) = when (value.lowercase()) {
            "amd64" -> "x86_64"
            "arm64" -> "aarch64"
            else -> value.lowercase()
        }
        val system = System.getProperty("os.name").let { if (it.startsWith("Mac")) "Darwin" else it }
        val arch = architecture(System.getProperty("os.arch"))
        val compiler = clang.get()
        val defaultTarget = run(listOf(compiler, "-dumpmachine")).trim()
        val parts = defaultTarget.split('-')
        require(system in setOf("Linux", "Darwin") && arch in setOf("x86_64", "aarch64") &&
            parts.size >= 3 && architecture(parts[0]) == arch &&
            (if (system == "Linux") parts.drop(2) == listOf("linux", "gnu") else parts[2].startsWith("darwin"))) {
            "Original stdio requires a native Linux GNU/macOS LP64 compiler: $system/$arch, clang=$defaultTarget"
        }
        val target = if (system == "Linux") "$arch-unknown-linux-gnu" else defaultTarget
        val command = listOf(compiler) + if (system == "Linux") listOf("--target=$target") else emptyList()
        require(run(command + "-dumpmachine").trim() == target) { "Clang did not select the native stdio target $target" }
        val executable = temporaryDir.resolve("stdio-abi-probe")
        run(command + listOf("-std=c11", source.path, "-o", executable.path))
        val probe = JsonSlurper().parseText(run(listOf(executable.path))) as Map<*, *>
        require(probe.keys == setOf("widths", "errno", "seek", "open")) { "Malformed native stdio ABI probe" }
        // The C probe asserts widths; runtime Kotlin validates all exact fields and errno values.
        val manifest = linkedMapOf<String, Any?>("schema" to 1, "system" to system,
            "architecture" to arch, "target" to target, "compilerDefaultTarget" to defaultTarget,
            "compilerVersion" to run(listOf(compiler, "--version")),
            "sourceSha256" to MessageDigest.getInstance("SHA-256").digest(source.readBytes())
                .joinToString("") { "%02x".format(it) },
            "widths" to probe["widths"], "errno" to probe["errno"], "seek" to probe["seek"], "open" to probe["open"])
        val destination = output.get().asFile.resolve("thc/native/stdio-host-abi.json")
        destination.parentFile.mkdirs()
        destination.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(manifest)) + "\n")
        logger.lifecycle("Probed original stdio ABI and errno values for $target")
    }
}
sourceSets.main { resources.srcDir(layout.buildDirectory.dir("generated/stdio-abi")) }
tasks.processResources { dependsOn(generateStdioAbi) }

val generatePosixStatAbi by tasks.registering {
    dependsOn(generateStdioAbi)
    val source = layout.projectDirectory.file("src/main/c/posix-stat-abi-probe.c").asFile
    val stdio = layout.buildDirectory.file("generated/stdio-abi/thc/native/stdio-host-abi.json")
    val output = layout.buildDirectory.dir("generated/posix-stat-abi")
    val clang = providers.environmentVariable("THC_CLANG").orElse("clang")
    inputs.file(source); inputs.file(stdio); inputs.property("clang", clang)
    outputs.dir(output); outputs.upToDateWhen { false }
    doLast {
        fun run(command: List<String>) = providers.exec { commandLine(command) }.standardOutput.asText.get()
        // Share the already validated native compiler target, not another list
        // of target triples or guessed ABI sizes.
        val host = JsonSlurper().parse(stdio.get().asFile) as Map<*, *>
        val target = host["target"] as String
        val command = listOf(clang.get()) + if (host["system"] == "Linux") listOf("--target=$target") else emptyList()
        require(run(command + "-dumpmachine").trim() == target) { "Stat compiler target changed" }
        val executable = temporaryDir.resolve("posix-stat-abi-probe")
        run(command + listOf("-std=c11", source.path, "-o", executable.path))
        val probe = JsonSlurper().parseText(run(listOf(executable.path))) as Map<*, *>
        val manifest = linkedMapOf<String, Any?>("schema" to 1, "system" to host["system"],
            "architecture" to host["architecture"], "target" to target,
            "sourceSha256" to MessageDigest.getInstance("SHA-256").digest(source.readBytes()).joinToString("") { "%02x".format(it) },
            "stat" to probe)
        val destination = output.get().asFile.resolve("thc/native/posix-stat-abi.json")
        destination.parentFile.mkdirs()
        destination.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(manifest)) + "\n")
    }
}
sourceSets.main { resources.srcDir(layout.buildDirectory.dir("generated/posix-stat-abi")) }
tasks.processResources { dependsOn(generatePosixStatAbi) }

val generateTermiosAbi by tasks.registering {
    dependsOn(generateStdioAbi)
    val source = layout.projectDirectory.file("src/main/c/termios-abi-probe.c").asFile
    val stdio = layout.buildDirectory.file("generated/stdio-abi/thc/native/stdio-host-abi.json")
    val output = layout.buildDirectory.dir("generated/termios-abi")
    val clang = providers.environmentVariable("THC_CLANG").orElse("clang")
    inputs.file(source); inputs.file(stdio); inputs.property("clang", clang)
    outputs.dir(output); outputs.upToDateWhen { false }
    doLast {
        fun run(command: List<String>) = providers.exec { commandLine(command) }.standardOutput.asText.get()
        val host = JsonSlurper().parse(stdio.get().asFile) as Map<*, *>
        val target = host["target"] as String
        val command = listOf(clang.get()) + if (host["system"] == "Linux") listOf("--target=$target") else emptyList()
        require(run(command + "-dumpmachine").trim() == target) { "Termios compiler target changed" }
        val executable = temporaryDir.resolve("termios-abi-probe")
        run(command + listOf("-std=c11", source.path, "-o", executable.path))
        val probe = JsonSlurper().parseText(run(listOf(executable.path))) as Map<*, *>
        val manifest = linkedMapOf<String, Any?>("schema" to 1, "system" to host["system"],
            "architecture" to host["architecture"], "target" to target,
            "sourceSha256" to MessageDigest.getInstance("SHA-256").digest(source.readBytes()).joinToString("") { "%02x".format(it) },
            "termios" to probe)
        val destination = output.get().asFile.resolve("thc/native/termios-abi.json")
        destination.parentFile.mkdirs()
        destination.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(manifest)) + "\n")
    }
}
sourceSets.main { resources.srcDir(layout.buildDirectory.dir("generated/termios-abi")) }
tasks.processResources { dependsOn(generateTermiosAbi) }

val generateSigsetAbi by tasks.registering {
    dependsOn(generateStdioAbi)
    val source = layout.projectDirectory.file("src/main/c/sigset-abi-probe.c").asFile
    val stdio = layout.buildDirectory.file("generated/stdio-abi/thc/native/stdio-host-abi.json")
    val output = layout.buildDirectory.dir("generated/sigset-abi")
    val clang = providers.environmentVariable("THC_CLANG").orElse("clang")
    inputs.file(source); inputs.file(stdio); inputs.property("clang", clang)
    outputs.dir(output); outputs.upToDateWhen { false }
    doLast {
        fun run(command: List<String>) = providers.exec { commandLine(command) }.standardOutput.asText.get()
        val host = JsonSlurper().parse(stdio.get().asFile) as Map<*, *>
        val target = host["target"] as String
        val command = listOf(clang.get()) + if (host["system"] == "Linux") listOf("--target=$target") else emptyList()
        require(run(command + "-dumpmachine").trim() == target) { "Sigset compiler target changed" }
        val executable = temporaryDir.resolve("sigset-abi-probe")
        run(command + listOf("-std=c11", source.path, "-o", executable.path))
        val probe = JsonSlurper().parseText(run(listOf(executable.path))) as Map<*, *>
        val manifest = linkedMapOf<String, Any?>("schema" to 1, "system" to host["system"],
            "architecture" to host["architecture"], "target" to target,
            "sourceSha256" to MessageDigest.getInstance("SHA-256").digest(source.readBytes()).joinToString("") { "%02x".format(it) },
            "sigset" to probe)
        val destination = output.get().asFile.resolve("thc/native/sigset-abi.json")
        destination.parentFile.mkdirs()
        destination.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(manifest)) + "\n")
    }
}
sourceSets.main { resources.srcDir(layout.buildDirectory.dir("generated/sigset-abi")) }
tasks.processResources { dependsOn(generateSigsetAbi) }
