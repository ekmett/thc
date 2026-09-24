// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

plugins {
    application
    kotlin("jvm") version "2.4.20"
    kotlin("kapt") version "2.4.20"
}
repositories { mavenCentral() }
// The checked family table generates concrete primitive carriers and typed nodes.
// BytecodeRoot's DSL requires nested declarations; its marked regions are checked,
// never rewritten by a build. Refresh them explicitly with the generator --write.
val generateSimdFamilies = tasks.register<Exec>("generateSimdFamilies") {
    inputs.files("scripts/generate-simd-families.py", "scripts/simd-families.json",
        "src/main/java/thc/runtime/BytecodeRoot.java", "src/main/kotlin/thc/runtime/BytecodeProgram.kt")
    outputs.dir(layout.buildDirectory.dir("generated/simd"))
    commandLine("python3", "scripts/generate-simd-families.py", "--check")
}
sourceSets.main { java.srcDir(layout.buildDirectory.dir("generated/simd/java")) }
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
            "floating-tuple/**/*.json", "floating-tuple/*.tsv", "floating-tuple/native/**",
            "tuple-input/**/*.json", "tuple-input/*.tsv", "tuple-input/native/**",
            "io-main-pap/**/*.json", "io-main-pap/*.tsv", "io-main-pap/native/**",
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
            "managed-mvars/**/*.json", "managed-mvars/*.tsv", "managed-mvars/native/**",
            "managed-md5-native/**",
            "pinned-addresses/**/*.json", "pinned-addresses/*.tsv", "pinned-addresses/native/**",
            "scalar-bitcasts/**/*.json", "scalar-bitcasts/*.tsv", "scalar-bitcasts/NativeScalarBitCast.hs", "scalar-bitcasts/native/**",
            "compare-byte-arrays/**/*.json", "compare-byte-arrays/*.tsv", "compare-byte-arrays/NativeCompareByteArrays.hs", "compare-byte-arrays/native/**",
            "bytearray/**/*.json", "bytearray/oracle.tsv", "bytearray/NativeByteArray.hs",
            "mutable-bytearray-size/**/*.json", "mutable-bytearray-size/*.tsv", "mutable-bytearray-size/native/**",
            "resize-bytearrays/**/*.json", "resize-bytearrays/*.tsv", "resize-bytearrays/native/**",
            "mutable-bytearrays/**/*.json", "mutable-bytearrays/*.tsv", "mutable-bytearrays/NativeMutableByteArrays.hs", "mutable-bytearrays/native/**",
            "array-slices/**/*.json", "array-slices/*.tsv", "array-slices/NativeArraySlices.hs", "array-slices/native/**",
            "boxed-arrays/**/*.json", "boxed-arrays/*.tsv", "boxed-arrays/NativeBoxedArray.hs", "boxed-arrays/native/**",
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
            "simd-capability-smoke/**/*.json", "simd-capability-smoke/*.tsv", "generated/simd/fixtures/*.hs",
            "explicit64-primops/core/**/*.json", "explicit64-primops/manifest.json", "explicit64-primops/oracle.tsv",
            "simd-int32x4/pre-core/**/*.json", "simd-int32x4/post-core/**/*.json", "simd-int32x4/oracle.tsv",
            "simd-floatx4/**/*.json", "simd-floatx4/*.tsv",
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
    inputs.files(fileTree("test/haskell-fixtures") { include("**/*.hs") })
    inputs.files(fileTree("vendor/ghc-9.14.1") { include("**/*.hs", "**/*.hs-boot", "LICENSE") })
    inputs.files(fileTree("scripts") {
        include("simd-families.json", "generate-simd-families.py", "prepare-simd-families.py",
            "prepare-simd-capability-smoke.py", "simd_family_model.py", "test-simd-families.py")
        include("prepare-corpus.py", "prepare-floating-audit.py", "prepare-floating-tuples.py", "prepare-sqrt-audit.py", "prepare-scalar-bitcasts.py", "scalar_bitcast_model.py", "test-scalar-bitcasts.py", "prepare-tag-to-enum-audit.py", "prepare-unsafe-equality-audit.py", "prepare-show-int.py", "show_int_model.py", "test-show-int-model.py", "prepare-narrow-literal-proofs.py", "test-narrow-literal-proofs.py", "prepare-bignat-literals.py", "bignat_literal_model.py", "test-bignat-literals.py", "prepare-show-word-list.py", "show_word_list_model.py", "test-show-word-list-model.py", "prepare-short-bytes-slices.py", "short_bytes_slice_model.py", "test-short-bytes-slices-model.py", "test-core-enums.py", "prepare-bytearray.py", "prepare-mutable-bytearray-size.py", "prepare-resize-bytearrays.py", "prepare-mutable-bytearrays.py", "mutable_bytearray_model.py", "test-mutable-bytearray-model.py", "prepare-compare-byte-arrays.py", "prepare-boxed-arrays.py", "prepare-array-slices.py", "test-array-slice-model.py", "prepare-mutvar.py", "prepare-int-arrays.py", "test-int-array-model.py", "prepare-tuple-arithmetic.py",
            "prepare-simd-audit.py", "prepare-floatx4-audit.py", "prepare-doublex2-audit.py", "doublex2_model.py", "test-doublex2-model.py", "core_vectors.py",
            "prepare-state-tuple-audit.py", "prepare-empty-tuple-input-audit.py", "prepare-tuple-input-audit.py", "prepare-io-main-pap.py", "test-tuple-inputs.py", "prepare-empty-join-input.py", "test-empty-join-inputs.py", "core_*.py", "generate-scalar-signatures.py",
            "prepare-double-arrays.py", "test-double-array-model.py",
            "prepare-managed-mvars.py", "test-managed-mvar-fixtures.py", "test-managed-mvars.py",
            "prepare-managed-md5.py", "prepare-pinned-addresses.py",
            "prepare-int32-arrays.py", "test-int32-array-model.py",
            "prepare-float-word-arrays.py", "test-float-word-array-model.py",
            "prepare-int16-arrays.py", "test-int16-array-model.py",
            "prepare-int8-arrays.py", "test-int8-array-model.py",
            "prepare-int16x8-audit.py", "int16x8_model.py", "test-int16x8-model.py",
            "prepare-address-fields.py", "test-address-fields.py",
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
    inputs.files("scripts/build-cbits.py", "src/main/c/md5-api.c",
        "bench/experiments/pinned-addresses/reference/md5.c",
        "bench/experiments/pinned-addresses/reference/md5.h")
    outputs.dir(layout.buildDirectory.dir("generated/cbits"))
    outputs.upToDateWhen { false }
    commandLine("python3", "scripts/build-cbits.py", "--output", layout.buildDirectory.dir("generated/cbits").get().asFile)
}
sourceSets.main { resources.srcDir(layout.buildDirectory.dir("generated/cbits")) }
tasks.processResources { dependsOn(compileCbits) }
