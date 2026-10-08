// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import thc.Json;

/** Checks the real provider inventory and version-script transformation without linking
 * or loading native code. Supply two explicit metadata paths, produced from the pinned
 * Linux GraalVM JDK by its declared LLVM tool (for example /usr/lib/llvm-20/bin/llvm-readobj):
 *
 * <pre>
 * "$LLVM_READOBJ" --dyn-symbols --dynamic-table --sections --elf-output-style=JSON \
 *   "$JAVA_HOME/lib/libjsvml.so" > "$SHARED_METADATA"
 * "$LLVM_READOBJ" --symbols --elf-output-style=JSON \
 *   "$JAVA_HOME/lib/static/linux-amd64/glibc/libjsvml.a" > "$ARCHIVE_METADATA"
 * java StaticVectorLibraryTest "$SHARED_METADATA" "$ARCHIVE_METADATA"
 * </pre>
 * Compile against the production StaticVectorLibrary, thc.Json and pinned SVM jars.
 * This protects complete export retention and rejection of incompatible metadata/scripts;
 * it does not establish native linkage, isolate initialization or vector execution.
 */
@SuppressWarnings("unchecked")
public final class StaticVectorLibraryTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void rejected(Runnable action, String reason) {
        try {
            action.run();
            throw new AssertionError("Accepted " + reason);
        } catch (IllegalArgumentException expected) {}
    }

    private static List<Map<String,Object>> read(Path path) throws Exception {
        return (List<Map<String,Object>>) Json.parse(Files.readString(path));
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("SHARED_METADATA ARCHIVE_METADATA required");
        var shared = read(Path.of(args[0]));
        var archive = read(Path.of(args[1]));
        var markers = new TreeSet<String>();
        var exports = StaticVectorLibrary.exports(shared, archive, markers);
        check(!exports.isEmpty(), "Actual provider has no retained exports");

        String script = "V1 {\nglobal:\n\"existing_image_symbol\";\nlocal: *;\n};\n";
        String merged = StaticVectorLibrary.mergeScript(script, exports);
        check(merged.startsWith("V1 {\nglobal:\n\"existing_image_symbol\";\n") &&
            merged.endsWith("local: *;\n};\n"), "Existing globals, version or local policy changed");
        for (String name : exports)
            check(merged.contains("\"" + name + "\";\n"), "Lost provider export: " + name);
        check(StaticVectorLibrary.mergeScript(merged, exports).equals(merged), "Repeated merge changes script");
        rejected(() -> StaticVectorLibrary.mergeScript("{ global: *; };", exports), "incompatible script");

        String missing = exports.getFirst();
        for (var member : archive)
            ((List<Map<String,Object>>) member.get("Symbols")).removeIf(row ->
                missing.equals(((Map<?,?>) ((Map<?,?>) row.get("Symbol")).get("Name")).get("Name")));
        rejected(() -> StaticVectorLibrary.exports(shared, archive, new TreeSet<>()), "missing real export " + missing);

        var validArchive = read(Path.of(args[1]));
        check(markers.contains("_init"), "Provider lacks the independently checked DT_INIT marker control");
        var symbols = (List<Map<String,Object>>) shared.getFirst().get("DynamicSymbols");
        var marker = symbols.stream().map(row -> (Map<String,Object>) row.get("Symbol"))
            .filter(row -> "_init".equals(((Map<?,?>) row.get("Name")).get("Name")))
            .findFirst().orElseThrow();
        marker.put("Value", ((Number) marker.get("Value")).longValue() + 1);
        rejected(() -> StaticVectorLibrary.exports(shared, validArchive, new TreeSet<>()), "incompatible marker address");
        System.out.println("PASS provider exports retained; missing export, incompatible marker/script rejected; script policy preserved");
    }
}
