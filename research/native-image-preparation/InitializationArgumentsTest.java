// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.classfile.instruction.ExceptionCatch;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.ToolProvider;

/** Exercises the real prepare-only recipe with isolated, real Java class files.
 * No image builder, guest execution or native resources. */
public final class InitializationArgumentsTest {
    private static Path recipe;
    private static Path output;
    private static Path javaHome;
    private static int checks;

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }

    private static Path fixture(String name, boolean classes, String pure, String extra) throws Exception {
        Path root = output.resolve(name);
        Files.createDirectories(root.resolve("bin/native-image"));
        Files.createDirectories(root.resolve("recipe"));
        Files.createDirectories(root.resolve("build/install/thc/lib"));
        for (String file : List.of("prepared-image.sh", "ClassInitializationInventory.java")) {
            Files.copy(recipe.resolve(file), root.resolve("recipe").resolve(file));
        }
        Path foreign = recipe.resolve("process-identity/reachability-metadata.json");
        if (Files.exists(foreign)) {
            Files.createDirectories(root.resolve("recipe/process-identity"));
            Files.copy(foreign, root.resolve("recipe/process-identity/reachability-metadata.json"));
        }
        Files.writeString(root.resolve("bin/native-image/pure-initialization.txt"), pure);
        Files.writeString(root.resolve("recipe/prepared-initialization.txt"), extra);
        Path compiled = root.resolve("classes");
        Files.createDirectories(compiled);
        if (classes) {
            Path source = root.resolve("Metadata.java");
            Files.writeString(source, """
                package thc.fixture;
                class Plain {}
                class Marker {
                    static final Marker INSTANCE = new Marker();
                    private Marker() {}
                }
                enum Tag { A, B }
                """);
            int result = ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-d", compiled.toString(), source.toString());
            check(result == 0, "fixture javac failed");
        }
        try (var jar = new JarOutputStream(Files.newOutputStream(
                root.resolve("build/install/thc/lib/thc-0.1-experiment.jar")));
             var files = Files.walk(compiled)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                jar.putNextEntry(new JarEntry(compiled.relativize(file).toString().replace('\\', '/')));
                Files.copy(file, jar);
                jar.closeEntry();
            }
        }
        return root;
    }

    private static int prepare(Path root) throws Exception {
        return prepare(root, null);
    }

    private static int prepare(Path root, String processIdentity) throws Exception {
        return prepare(root, processIdentity, "prepare-only");
    }
    private static int prepare(Path root, String processIdentity, String mode) throws Exception {
        return prepare(root, processIdentity, mode, null);
    }
    private static int prepare(Path root, String processIdentity, String mode, String vectorProfile) throws Exception {
        return prepare(root, processIdentity, mode, vectorProfile, null);
    }
    private static int prepare(Path root, String processIdentity, String mode, String vectorProfile, String builderHeap) throws Exception {
        var command = new ProcessBuilder("bash", root.resolve("recipe/prepared-image.sh").toString(),
            root.toString(), mode);
        command.environment().put("JAVA_HOME", javaHome.toString());
        command.environment().remove("THC_NATIVE_IMAGE_PROCESS_IDENTITY");
        command.environment().remove("THC_NATIVE_IMAGE_VECTOR_PROFILE");
        command.environment().remove("THC_NATIVE_IMAGE_BUILDER_HEAP");
        command.environment().remove("THC_NATIVE_IMAGE_AUTOVECTORIZE");
        if (processIdentity != null) command.environment().put("THC_NATIVE_IMAGE_PROCESS_IDENTITY", processIdentity);
        if (vectorProfile != null) command.environment().put("THC_NATIVE_IMAGE_VECTOR_PROFILE", vectorProfile);
        if (builderHeap != null) command.environment().put("THC_NATIVE_IMAGE_BUILDER_HEAP", builderHeap);
        command.redirectErrorStream(true).redirectOutput(root.resolve("prepare.log").toFile());
        return command.start().waitFor();
    }

    private static String inventory(Path root, String name) throws Exception {
        return Files.readString(root.resolve("build/native-image/reproduction-inventory").resolve(name));
    }

    private static String switches(Path root, String approved) throws Exception {
        var command = new ProcessBuilder(javaHome.resolve("bin/java").toString(), "-XX:-UseJVMCICompiler",
            "-cp", root.resolve("build/native-image/reproduction-probe").toString(),
            "ClassInitializationInventory", root.resolve("build/install/thc/lib/thc-0.1-experiment.jar").toString(),
            "switches", approved);
        command.redirectError(root.resolve("switches.err").toFile());
        var process = command.start();
        var result = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        check(process.waitFor() == 0, "switch classifier failed");
        return result;
    }

    private static void switchChecks() throws Exception {
        var root = fixture("switches", false, "thc.fixture.SafeTag\nthc.fixture.OtherTag\n", "# empty\n");
        var source = root.resolve("Switches.java");
        Files.writeString(source, """
            package thc.fixture;
            enum SafeTag { A, B }
            enum OtherTag { C, D }
            enum StatefulTag { E; static { throwIfInitialized(); }
                static void throwIfInitialized() { throw new AssertionError("must not initialize"); }
            }
            class SafeSwitch {
                static int select(SafeTag value) { return switch(value) { case A -> 1; case B -> 2; }; }
                static int select(OtherTag value) { return switch(value) { case C -> 3; case D -> 4; }; }
            }
            class StatefulSwitch {
                static int select(StatefulTag value) { return switch(value) { case E -> 5; }; }
            }
            class ForeignState { static int value; }
            """);
        var compiled = root.resolve("classes");
        check(ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", compiled.toString(), source.toString()) == 0,
            "switch fixture javac failed");
        var holder = compiled.resolve("thc/fixture/SafeSwitch$1.class");
        var original = Files.readAllBytes(holder);
        var cf = ClassFile.of();
        var model = cf.parse(original);
        var jar = root.resolve("build/install/thc/lib/thc-0.1-experiment.jar");
        writeClasses(jar, compiled);
        check(prepare(root) == 0, "switch preparation failed");
        check(inventory(root, "switches.txt").equals("thc.fixture.SafeSwitch$1\n"),
            "admit multi-map holder only for prepared enum dependencies");
        check(switches(root, "thc.fixture.SafeTag").equals("\n"), "do not automatically prepare the second enum");
        check(switches(root, "").equals("\n"), "no approved enums means no switch holders");
        check(switches(root, "thc.fixture.SafeTag,thc.fixture.OtherTag").equals("thc.fixture.SafeSwitch$1\n"),
            "stateful unapproved enum and its holder stay out; no initializers execute");

        // Keep the real synthetic holder and fields, but add an unexpected
        // effect or change its handler. Flags/names alone must not admit it.
        for (String mutation : List.of("call", "foreign write", "handler")) {
            Files.write(holder, cf.transformClass(model, ClassTransform.transformingMethodBodies((builder, element) -> {
                if (mutation.equals("handler") && element instanceof ExceptionCatch handler) {
                    builder.exceptionCatch(handler.tryStart(), handler.tryEnd(), handler.handler(), ClassDesc.of("java.lang.RuntimeException"));
                    return;
                }
                if (!mutation.equals("handler") && element instanceof ReturnInstruction instruction && instruction.opcode() == Opcode.RETURN) {
                    if (mutation.equals("foreign write")) builder.iconst_1().putstatic(ClassDesc.of("thc.fixture.ForeignState"), "value", java.lang.constant.ConstantDescs.CD_int);
                    else builder.invokestatic(ClassDesc.of("java.lang.System"), "nanoTime",
                        MethodTypeDesc.of(java.lang.constant.ConstantDescs.CD_long)).pop2();
                }
                builder.with(element);
            })));
            writeClasses(jar, compiled);
            check(switches(root, "thc.fixture.SafeTag,thc.fixture.OtherTag").equals("\n"),
                "reject synthetic holder with unexpected " + mutation);
        }
    }

    private static void writeClasses(Path jar, Path compiled) throws Exception {
        try (var output = new JarOutputStream(Files.newOutputStream(jar)); var files = Files.walk(compiled)) {
            for (var file : files.filter(Files::isRegularFile).sorted().toList()) {
                output.putNextEntry(new JarEntry(compiled.relativize(file).toString().replace('\\', '/')));
                Files.copy(file, output);
                output.closeEntry();
            }
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("RECIPE OUTPUT required");
        recipe = Path.of(args[0]).toAbsolutePath();
        output = Path.of(args[1]).toAbsolutePath();
        javaHome = Path.of(System.getenv("JAVA_HOME"));
        Files.createDirectories(output);
        Path mixed = fixture("named-categories", true, "# pure\nthc.fixture.Plain\n",
            "# extra\nthc.fixture.Tag\n");
        check(prepare(mixed) == 0, "mixed prepare failed; see prepare.log");
        check(inventory(mixed, "stateless.txt").equals("thc.fixture.Plain\n"), "stateless fixture");
        check(inventory(mixed, "markers.txt").equals("thc.fixture.Marker\n"), "marker fixture");
        check(inventory(mixed, "enums.txt").equals("thc.fixture.Tag\n"), "enum fixture");
        check(inventory(mixed, "prepared-initialization.args").equals(
            "--initialize-at-build-time=thc.fixture.Plain,thc.fixture.Plain,thc.fixture.Marker,thc.fixture.Tag,thc.fixture.Tag\n"),
            "named generated categories must preserve order and duplicates");

        // One installed runtime can feed multiple targets. Preparation must own
        // only its selected output tree and accept already-populated directories.
        Path isolated = fixture("separate-output", true, "# pure\nthc.fixture.Plain\n",
            "# extra\nthc.fixture.Tag\n");
        Path selected = output.resolve("-target output");
        Files.createDirectories(selected);
        Files.writeString(selected.resolve("keep.txt"), "caller-owned content");
        for (int pass = 0; pass < 2; pass++) {
            var command = new ProcessBuilder("bash", isolated.resolve("recipe/prepared-image.sh").toString(),
                isolated.toString(), "prepare-only", "-target output");
            command.directory(output.toFile());
            command.environment().put("JAVA_HOME", javaHome.toString());
            command.environment().keySet().removeIf(key -> key.startsWith("THC_NATIVE_IMAGE_"));
            command.redirectErrorStream(true).redirectOutput(selected.resolve("prepare.log").toFile());
            check(command.start().waitFor() == 0, "prepare in an explicit build directory");
            check(Files.readString(selected.resolve("reproduction-inventory/prepared-initialization.args"))
                .equals(inventory(mixed, "prepared-initialization.args")), "output location must not change initialization");
            check(Files.exists(selected.resolve("reproduction-probe/ClassInitializationInventory.class")),
                "compiled inventory tool belongs to the selected build directory");
            check(!Files.exists(isolated.resolve("build/native-image")), "no shared default-directory outputs");
            check(Files.readString(selected.resolve("keep.txt")).equals("caller-owned content"),
                "preparation preserves unrelated output files");
        }

        Path empty = fixture("empty-generated", false, "thc.fixture.Plain\n", "thc.fixture.Tag\n");
        check(prepare(empty) == 0, "empty-generated prepare failed");
        check(inventory(empty, "prepared-initialization.args").equals(
            "--initialize-at-build-time=thc.fixture.Plain,thc.fixture.Tag\n"), "skip all empty generated categories");

        Path manual = fixture("manual-only", false, "# empty\n", "thc.fixture.Tag\n");
        check(prepare(manual) == 0, "manual-only prepare failed");
        check(inventory(manual, "prepared-initialization.args").equals(
            "--initialize-at-build-time=thc.fixture.Tag\n"), "manual-only inventory must not gain an empty prefix");

        Path none = fixture("empty-everything", false, "# empty\n", "\n# empty\n");
        check(prepare(none) == 2, "reject an entirely empty inventory rather than emitting global initialization");
        check(!Files.exists(none.resolve("build/native-image/reproduction-inventory/prepared-initialization.args")),
            "empty inventory must not publish an argument file");

        Path invalid = fixture("invalid-manual", false, "thc.fixture.Plain\n", "thc.fixture.Tag,thc.fixture.Plain\n");
        check(prepare(invalid) == 2, "manual inventory must still reject comma-separated entries");

        Path foreign = fixture("process-identity", false, "thc.fixture.Plain\n", "thc.fixture.Tag\n");
        check(prepare(foreign, "1") == 0, "process-identity prepare failed");
        Path foreignArgs = foreign.resolve("build/native-image/reproduction-inventory/foreign.args");
        check(Files.exists(foreignArgs), "prepare-only must record the selected foreign-registration arguments");
        check(Files.readString(foreignArgs).equals("\"-H:ConfigurationFileDirectories=" +
            foreign.resolve("recipe/process-identity") + "\"\n"), "select only the process-identity configuration directory");
        check(Files.readString(recipe.resolve("process-identity/reachability-metadata.json")).replaceAll("\\s+", "")
            .equals("{\"foreign\":{\"downcalls\":[{\"returnType\":\"jint\",\"parameterTypes\":[]}]}}"),
            "register precisely the observed jint() signature, no resources, upcalls or additional signatures");
        check(prepare(foreign) == 0, "default prepare after opt-in failed");
        check(Files.readString(foreignArgs).isEmpty(), "default preparation must clear a prior opt-in, not retain stale registration");
        check(prepare(foreign, "true") == 2, "reject misspelled opt-in values");
        Files.writeString(manual.resolve("recipe/cache-initialization.txt"), "# explicit cache holder\nthc.fixture.Marker\n");
        boolean linuxAmd64 = System.getProperty("os.name").equals("Linux") &&
            java.util.Set.of("amd64", "x86_64").contains(System.getProperty("os.arch"));
        check(prepare(manual, null, "cache-prepare-only") == (linuxAmd64 ? 0 : 2), "cache recipe host restriction");
        if (linuxAmd64) check(inventory(manual, "prepared-initialization.args").equals(
            "--initialize-at-build-time=thc.fixture.Tag,thc.fixture.Marker\n"), "cache mode adds only its explicit finite inventory");
        check(prepare(manual) == 0, "ordinary preparation after cache mode");
        check(inventory(manual, "prepared-initialization.args").equals(
            "--initialize-at-build-time=thc.fixture.Tag\n"), "ordinary image must not inherit cached-mode holders");
        check(prepare(manual, null, "prepare-only", "resource-copy") == 0, "resource-copy profile preparation");
        check(inventory(manual, "vector-profile.args").equals(
            "-H:-Vectorization\n-R:-Vectorization\n-H:-VectorAPISupport\n-H:+SharedArenaSupport\n-Dthc.nativeImage.resourceCopies=true\n"),
            "shared arenas and the image-only memory fallback must be selected together");
        check(prepare(manual) == 0 && inventory(manual, "vector-profile.args").equals(
            "-H:-Vectorization\n-R:-Vectorization\n-H:+OptimizeVectorAPI\n-H:+TargetVectorLowering\n" +
            "-R:+OptimizeVectorAPI\n-R:+TargetVectorLowering\n-H:+SharedArenaSupport\n"),
            "default image restores direct API lowering after global disable and must not inherit copying flags");
        check(prepare(manual, null, "prepare-only", "typo") == 2, "unknown vector profile rejected");
        check(inventory(manual, "builder-heap.args").equals("-J-Xmx8g\n"), "generic builder default remains 8 GiB");
        check(prepare(manual, null, "prepare-only", null, "16g") == 0 &&
            inventory(manual, "builder-heap.args").equals("-J-Xmx16g\n"), "explicit 16 GiB builder heap only");
        check(prepare(manual, null, "prepare-only", null, "24g") == 2, "unqualified larger builder heap rejected");
        check(prepare(manual, null, "prepare-only", null, "16g -Xmx32g") == 2, "builder option injection rejected");
        check(prepare(manual) == 0 && inventory(manual, "builder-heap.args").equals("-J-Xmx8g\n"),
            "default preparation clears a prior builder override");
        switchChecks();
        System.out.println("PASS " + checks + " initialization-argument checks; prepare-only, no image execution");
    }
}
