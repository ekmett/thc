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
 *   "$JAVA_HOME/lib/libjsvml.a" > "$ARCHIVE_METADATA"
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

    private static Map<String,Object> symbol(String name, String type, String section, long value, long size) {
        return new java.util.LinkedHashMap<>(Map.of("Name", Map.of("Name", name), "Type", Map.of("Name", type),
            "Section", Map.of("Name", section), "Binding", Map.of("Name", "Global"), "Value", value, "Size", size));
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

        // Independent ELF model: a callable export plus a zero-sized linker
        // initializer at 0x1000. A real JDK need not export lifecycle markers.
        var callable = symbol("vector_function", "Function", ".text", 0x2000, 8);
        var marker = symbol("_init", "Function", ".init", 0x1000, 0);
        var markerProvider = Map.<String,Object>of(
            "FileSummary", Map.of("Format", "elf64-x86-64"),
            "DynamicSymbols", List.of(Map.of("Symbol", callable), Map.of("Symbol", marker)),
            "Sections", List.of(Map.of("Section", Map.of("Name", Map.of("Name", ".init"), "Address", 0x1000L))),
            "DynamicSection", List.of(Map.of("Type", "INIT", "Value", 0x1000L)));
        var markerArchive = Map.<String,Object>of("FileSummary", Map.of("Format", "elf64-x86-64"),
            "Symbols", List.of(Map.of("Symbol", callable)));
        var modelMarkers = new TreeSet<String>();
        check(StaticVectorLibrary.exports(List.of(markerProvider), List.of(markerArchive), modelMarkers)
            .equals(List.of("vector_function")) && modelMarkers.equals(java.util.Set.of("_init")),
            "linker marker must agree with its ELF section and dynamic tag");
        marker.put("Value", 0x1001L);
        rejected(() -> StaticVectorLibrary.exports(List.of(markerProvider), List.of(markerArchive), new TreeSet<>()),
            "incompatible marker address");
        System.out.println("PASS provider exports retained; missing export, incompatible marker/script rejected; script policy preserved");
    }
}
