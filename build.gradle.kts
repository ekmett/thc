plugins {
    application
    kotlin("jvm") version "2.4.20"
    kotlin("kapt") version "2.4.20"
}
repositories { mavenCentral() }
val graalVersion = "25.3.4.1"
dependencies {
    implementation(kotlin("stdlib"))
    implementation("org.graalvm.polyglot:polyglot:$graalVersion")
    implementation("org.graalvm.truffle:truffle-api:$graalVersion")
    runtimeOnly("org.graalvm.truffle:truffle-runtime:$graalVersion")
    kapt("org.graalvm.truffle:truffle-dsl-processor:$graalVersion")
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
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
tasks.test {
    useJUnitPlatform()
    // Exported Core and native expectations are test inputs even when JVM sources
    // are unchanged. Source inputs also make stale corpus fingerprints observable.
    inputs.files(fileTree(layout.buildDirectory) {
        include("core/**/*.json", "source-core/**/*.json", "cbv-post-core/**/*.json",
            "floating/core/**/*.json", "floating/checks.json", "floating/oracle.tsv",
            "floating-tuple/**/*.json", "floating-tuple/*.tsv", "floating-tuple/native/**",
            "empty-tuple-input/**/*.json", "empty-tuple-input/*.tsv", "empty-tuple-input/native/**",
            "sqrt/**/*.json", "sqrt/*.tsv", "sqrt/native/**",
            "tag-to-enum/**/*.json", "tag-to-enum/*.tsv", "tag-to-enum/native/**",
            "aggregate-core/**/*.json", "aggregate-post-core/**/*.json", "map/boot-core/**/*.json",
            "aggregate-layout/pre-core/**/*.json", "aggregate-layout/post-core/**/*.json",
            "sum-layout/**/*.json", "sum-layout/*.tsv", "sum-layout/native/**",
            "tuple-return/pre-core/**/*.json", "tuple-return/post-core/**/*.json", "tuple-return/oracle.tsv",
            "state-tuple/pre-core/**/*.json", "state-tuple/post-core/**/*.json", "state-tuple/oracle.tsv",
            "state-tuple/provenance.json", "state-tuple/*-audit.json", "state-tuple/native/**",
            "tuple-join/pre-core/**/*.json", "tuple-join/post-core/**/*.json", "tuple-join/oracle.tsv",
            "tuple-arithmetic/pre-core/**/*.json", "tuple-arithmetic/post-core/**/*.json",
            "tuple-arithmetic/manifest.json", "tuple-arithmetic/oracle.tsv",
            "integer-primops/core/**/*.json", "integer-primops/manifest.json", "integer-primops/oracle.tsv",
            "mutvar/**/*.json", "mutvar/oracle.tsv", "mutvar/NativeMutVar.hs",
            "bytearray/**/*.json", "bytearray/oracle.tsv", "bytearray/NativeByteArray.hs",
            "boxed-arrays/**/*.json", "boxed-arrays/*.tsv", "boxed-arrays/NativeBoxedArray.hs", "boxed-arrays/native/**",
            "int-arrays/**/*.json", "int-arrays/oracle.tsv", "int-arrays/expected.tsv",
            "int-arrays/NativeIntArray.hs", "int-arrays/native/int-array-oracle",
            "double-arrays/**/*.json", "double-arrays/oracle.tsv", "double-arrays/expected.tsv",
            "double-arrays/NativeDoubleArray.hs", "double-arrays/native/double-array-oracle",
            "int32-arrays/**/*.json", "int32-arrays/oracle.tsv", "int32-arrays/expected.tsv",
            "int32-arrays/literal-oracle.tsv",
            "int32-arrays/NativeInt32Array.hs", "int32-arrays/native/int32-array-oracle",
            "float-word-arrays/**/*.json", "float-word-arrays/oracle.tsv", "float-word-arrays/expected.tsv",
            "float-word-arrays/NativeFloatWordArray.hs", "float-word-arrays/native/float-word-array-oracle",
            "bit-primops/**/*.json", "bit-primops/oracle.tsv", "bit-primops/NativeBitPrimops.hs",
            "simd/pre-core/**/*.json", "simd/post-core/**/*.json", "simd/oracle.tsv",
            "explicit64-primops/core/**/*.json", "explicit64-primops/manifest.json", "explicit64-primops/oracle.tsv",
            "simd-int32x4/pre-core/**/*.json", "simd-int32x4/post-core/**/*.json", "simd-int32x4/oracle.tsv",
            "simd-floatx4/**/*.json", "simd-floatx4/*.tsv",
            "simd-doublex2/**/*.json", "simd-doublex2/*.tsv",
            "signed-narrow-primops/core/**/*.json", "signed-narrow-primops/manifest.json", "signed-narrow-primops/oracle.tsv",
            "corpus/**/*.json", "corpus/oracle.tsv", "native/oracle.tsv")
    })
    inputs.files(fileTree("examples") { include("**/*.hs", "coverage.json") })
    inputs.files(fileTree("compiler") { include("**/*.hs", "*.sh", "*.py") })
    inputs.files(fileTree("vendor/ghc-9.14.1") { include("**/*.hs", "**/*.hs-boot", "LICENSE") })
    inputs.files(fileTree("scripts") {
        include("prepare-corpus.py", "prepare-floating-audit.py", "prepare-floating-tuples.py", "prepare-sqrt-audit.py", "prepare-tag-to-enum-audit.py", "test-core-enums.py", "prepare-integer-primops.py", "prepare-bit-primops.py", "prepare-bytearray.py", "prepare-boxed-arrays.py", "prepare-mutvar.py", "prepare-int-arrays.py", "test-int-array-model.py", "prepare-tuple-arithmetic.py",
            "prepare-signed-narrow-primops.py", "prepare-explicit64-primops.py", "prepare-simd-audit.py", "prepare-floatx4-audit.py", "prepare-doublex2-audit.py", "doublex2_model.py", "test-doublex2-model.py", "core_vectors.py",
            "prepare-state-tuple-audit.py", "prepare-empty-tuple-input-audit.py", "core_*.py", "generate-scalar-signatures.py",
            "prepare-double-arrays.py", "test-double-array-model.py",
            "prepare-int32-arrays.py", "test-int32-array-model.py",
            "prepare-float-word-arrays.py", "test-float-word-array-model.py",
            "audit-core.py", "core-capabilities.json", "check-corpus-structure.py",
            "check-sum-layout.py", "sum_layout_model.py", "test-sum-layout.py")
    })
    jvmArgs(application.applicationDefaultJvmArgs)
    systemProperty("thc.projectRoot", projectDir.absolutePath)
    // Keep the default tests independent of THC_BACKEND; bytecode tests select their backend explicitly.
    systemProperty("thc.backend", "ast")
    testLogging { events("failed", "skipped", "passed") }
}
tasks.register<JavaExec>("probe") {
    group = "verification"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("thc.ProbeKt")
    jvmArgs(application.applicationDefaultJvmArgs)
    workingDir(projectDir)
}

// Vector intrinsics are isolated in Java; floating vectors retain fixed species.
tasks.withType<JavaCompile>().configureEach { options.compilerArgs.addAll(listOf("--add-modules", "jdk.incubator.vector")) }
