// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
        Files.createDirectories(root.resolve("scripts/native-image"));
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
        Files.writeString(root.resolve("scripts/native-image/pure-initialization.txt"), pure);
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
        var command = new ProcessBuilder("bash", root.resolve("recipe/prepared-image.sh").toString(),
            root.toString(), "prepare-only");
        command.environment().put("JAVA_HOME", javaHome.toString());
        command.environment().remove("THC_NATIVE_IMAGE_PROCESS_IDENTITY");
        if (processIdentity != null) command.environment().put("THC_NATIVE_IMAGE_PROCESS_IDENTITY", processIdentity);
        command.redirectErrorStream(true).redirectOutput(root.resolve("prepare.log").toFile());
        return command.start().waitFor();
    }

    private static String inventory(Path root, String name) throws Exception {
        return Files.readString(root.resolve("build/native-image/reproduction-inventory").resolve(name));
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
        System.out.println("PASS " + checks + " initialization-argument checks; prepare-only, no image execution");
    }
}
