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
application {
    mainClass.set("thc.MainKt")
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED", "-Xss2m")
}
tasks.test {
    useJUnitPlatform()
    jvmArgs(application.applicationDefaultJvmArgs)
    systemProperty("thc.projectRoot", projectDir.absolutePath)
    testLogging { events("failed", "skipped", "passed") }
}
tasks.register<JavaExec>("probe") {
    group = "verification"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("thc.ProbeKt")
    jvmArgs(application.applicationDefaultJvmArgs)
    workingDir(projectDir)
}
