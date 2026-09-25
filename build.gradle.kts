// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.security.MessageDigest

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
            "managed-mvars/**/*.json", "managed-mvars/*.tsv", "managed-mvars/native/**",
            "synchronous-exceptions/**/*.json", "synchronous-exceptions/*.tsv", "synchronous-exceptions/native/**",
            "core-continuation/**/*.json", "core-continuation/native-output.txt",
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
