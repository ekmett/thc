// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.graalvm.nativeimage.hosted.RuntimeClassInitialization;
import com.oracle.svm.core.LinkerInvocation;
import com.oracle.svm.core.jdk.NativeLibrarySupport;
import com.oracle.svm.guest.staging.jdk.RuntimeSupport;
import com.oracle.svm.hosted.c.NativeLibraries;
import thc.Json;

/** Pinned Linux JDK vector provider: retain its complete shared ABI in the real static archive. */
@SuppressWarnings("unchecked")
public final class StaticVectorLibrary {
    private final Path archive, shared, tool, receipt;
    private final Map<String,Object> inputs;
    private final List<String> exports;
    private static final Pattern SYMBOL = Pattern.compile("[A-Za-z_][A-Za-z0-9_.$]*");
    private static final Pattern SCRIPT = Pattern.compile("(?:[A-Za-z_][A-Za-z0-9_.]* )?\\{\\n(?:global:\\n(?:\"[^\"\\n]+\";\\n)*)?local: \\*;\\n};\\n");

    public StaticVectorLibrary(Path receipt) throws Exception {
        require(System.getProperty("os.name").equals("Linux") && System.getProperty("os.arch").equals("amd64"),
            "Static vector library requires the pinned Linux AMD64 JDK");
        this.receipt = receipt;
        var jdk = Path.of(System.getProperty("java.home")).toRealPath();
        archive = jdk.resolve("lib/static/linux-amd64/glibc/libjsvml.a").toRealPath();
        shared = jdk.resolve("lib/libjsvml.so").toRealPath();
        tool = tool(System.getenv().getOrDefault("THC_LLVM_READOBJ", "llvm-readobj"));
        var sharedMetadata = records(inspect("--dyn-symbols", "--dynamic-table", "--sections", shared.toString()));
        var archiveMetadata = records(inspect("--symbols", archive.toString()));
        var markers = new TreeSet<String>();
        exports = exports(sharedMetadata, archiveMetadata, markers);
        inputs = new LinkedHashMap<>();
        inputs.put("archive", identity(archive)); inputs.put("shared", identity(shared)); inputs.put("tool", identity(tool));
        var recipe = Path.of(Objects.requireNonNull(System.getProperty("thc.nativeImage.vectorRecipe"), "Vector recipe source directory required"));
        inputs.put("sources", List.of(identity(recipe.resolve("StaticVectorLibrary.java")),
            identity(recipe.resolve("PreparedInitializationFeature.java")), identity(recipe.resolve("prepared-image.sh"))));
        inputs.put("builder", List.of(identity(jdk.resolve("lib/svm/builder/svm.jar")),
            identity(jdk.resolve("lib/svm/builder/svm-guest-staging.jar"))));
        inputs.put("exports", exports); inputs.put("elfMarkers", List.copyOf(markers));
        var nativeInputs = (Map<String,Object>) Json.parse(Files.readString(receipt));
        nativeInputs.put("staticLibraries", List.of(Map.of("name", "jsvml", "inputs", inputs)));
        Files.writeString(receipt, Json.stringify(nativeInputs) + "\n");
        NativeLibrarySupport.singleton().preregisterUninitializedBuiltinLibrary("jsvml");
        // Only this fieldless hook is captured in the image; native initialization stays at runtime.
        RuntimeClassInitialization.initializeAtBuildTime(MarkLoaded.class);
        RuntimeSupport.getRuntimeSupport().addInitializationHook(new MarkLoaded());
    }

    public void select() { NativeLibraries.singleton().addStaticNonJniLibrary("jsvml"); }

    /** The existing per-isolate hook runs after native platform initialization, before user entry. */
    private static final class MarkLoaded implements RuntimeSupport.Hook {
        @Override public void execute(boolean firstIsolate) {
            NativeLibrarySupport.singleton().registerInitializedBuiltinLibrary("jsvml");
        }
    }

    public LinkerInvocation link(LinkerInvocation invocation) {
        try {
            require(identity(archive).equals(inputs.get("archive")) && identity(shared).equals(inputs.get("shared")) && identity(tool).equals(inputs.get("tool")),
                "Vector provider changed during image construction");
            boolean selected = false;
            for (var path : invocation.getInputFiles())
                if (Files.isRegularFile(path) && path.toRealPath().equals(archive)) selected = true;
            require(selected, "Linker did not select the inventoried vector archive");
            var script = invocation.getTempDirectory().resolve("exported_symbols.list").toAbsolutePath();
            require(Collections.frequency(invocation.getCommand(), "-Wl,--version-script," + script) == 1,
                "Pinned linker must use exactly the inventoried export script");
            String previous = Files.readString(script), merged = mergeScript(previous, exports);
            Files.writeString(script, merged);
            for (String name : exports) invocation.addNativeLinkerOption("-Wl,-u," + name);
            var nativeInputs = (Map<String,Object>) Json.parse(Files.readString(receipt));
            var recorded = new LinkedHashMap<>(inputs);
            recorded.put("originalScriptSHA256", digest(previous.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            recorded.put("mergedScriptSHA256", digest(merged.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            nativeInputs.put("staticLibraries", List.of(Map.of("name", "jsvml", "inputs", recorded)));
            Files.writeString(receipt, Json.stringify(nativeInputs) + "\n");
            return invocation;
        } catch (Exception failure) { throw new IllegalStateException("Cannot link the inventoried vector provider", failure); }
    }

    static String mergeScript(String script, List<String> exports) {
        require(SCRIPT.matcher(script).matches(), "Incompatible pinned linker export script");
        String added = script.contains("global:\n") ? "" : "global:\n";
        for (String name : exports) {
            require(SYMBOL.matcher(name).matches(), "Invalid vector export: " + name);
            if (!script.contains("\"" + name + "\";\n")) added += "\"" + name + "\";\n";
        }
        return script.replace("local: *;\n", added + "local: *;\n");
    }

    static List<String> exports(List<Map<String,Object>> shared, List<Map<String,Object>> archive, Set<String> markers) {
        require(shared.size() == 1 && !archive.isEmpty(), "Expected one shared provider and nonempty archive");
        var defined = new TreeMap<String,Set<String>>();
        for (var member : archive) {
            elf(member);
            for (var row : (List<Map<String,Object>>) member.get("Symbols")) {
                var symbol = (Map<String,Object>) row.get("Symbol");
                if (exported(symbol)) defined.computeIfAbsent(name(symbol, "Name"), ignored -> new TreeSet<>()).add(name(symbol, "Type"));
            }
        }
        var provider = shared.getFirst(); elf(provider);
        var result = new TreeSet<String>();
        for (var row : (List<Map<String,Object>>) provider.get("DynamicSymbols")) {
            var symbol = (Map<String,Object>) row.get("Symbol");
            if (!exported(symbol)) continue;
            String name = name(symbol, "Name");
            require(SYMBOL.matcher(name).matches(), "Unsupported vector export identity: " + name);
            if (defined.containsKey(name)) {
                require(defined.get(name).contains(name(symbol, "Type")), "Vector export type differs in archive: " + name);
                result.add(name);
            }
            else {
                require(marker(provider, symbol, name), "Shared vector export absent from archive: " + name);
                markers.add(name);
            }
        }
        require(!result.isEmpty(), "Vector provider has no archive-backed exports");
        return List.copyOf(result);
    }

    private static boolean exported(Map<String,Object> symbol) {
        return Set.of("Global", "Weak").contains(name(symbol, "Binding")) && !name(symbol, "Section").equals("Undefined");
    }
    private static void elf(Map<String,Object> record) {
        require("elf64-x86-64".equals(((Map<?,?>) record.get("FileSummary")).get("Format")), "Expected Linux AMD64 ELF metadata");
    }
    private static String name(Map<String,Object> map, String key) { return (String) ((Map<?,?>) map.get(key)).get("Name"); }
    private static long number(Map<String,Object> map, String key) { return ((Number) map.get(key)).longValue(); }

    // Standard linker boundary/lifecycle exports must also agree with ELF addresses and types.
    // Every unexplained shared-only symbol, including a new callable function, is rejected.
    private static boolean marker(Map<String,Object> provider, Map<String,Object> symbol, String name) {
        if (number(symbol, "Size") != 0) return false;
        String section = switch (name) { case "_init" -> ".init"; case "_fini" -> ".fini";
            case "__bss_start", "_edata", "_end" -> ".bss"; default -> ""; };
        if (section.isEmpty()) return false;
        var sections = ((List<Map<String,Object>>) provider.get("Sections")).stream()
            .map(row -> (Map<String,Object>) row.get("Section")).filter(row -> name(row, "Name").equals(section)).toList();
        if (sections.size() != 1) return false;
        var target = sections.getFirst(); long value = number(symbol, "Value"), start = number(target, "Address");
        if (section.equals(".bss")) return name(symbol, "Type").equals("None") && name(target, "Type").equals("SHT_NOBITS")
            && value == start + (name.equals("_end") ? number(target, "Size") : 0);
        String tag = name.equals("_init") ? "INIT" : "FINI";
        return name(symbol, "Type").equals("Function") && name(symbol, "Section").equals(section) && value == start
            && ((List<Map<String,Object>>) provider.get("DynamicSection")).stream()
                .anyMatch(entry -> tag.equals(entry.get("Type")) && number(entry, "Value") == value);
    }

    private static List<Map<String,Object>> records(String json) { return (List<Map<String,Object>>) Json.parse(json); }
    private String inspect(String... flags) throws Exception {
        var command = new ArrayList<>(List.of(tool.toString(), "--elf-output-style=JSON")); command.addAll(List.of(flags));
        var output = Files.createTempFile("thc-vector-metadata-", ".json"); Process process = null;
        try {
            var builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile());
            builder.environment().put("LC_ALL", "C"); builder.environment().remove("LD_PRELOAD"); builder.environment().remove("LD_AUDIT");
            process = builder.start(); require(process.waitFor(15, TimeUnit.SECONDS), "Vector inspection timed out");
            String text = Files.readString(output); require(process.exitValue() == 0, "Vector inspection failed: " + text); return text;
        } catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw failure; }
        finally {
            boolean interrupted = Thread.interrupted();
            if (process != null) {
                if (process.isAlive()) process.destroyForcibly();
                while (process.isAlive()) try { process.waitFor(); }
                    catch (InterruptedException failure) { interrupted = true; process.destroyForcibly(); }
            }
            Files.deleteIfExists(output);
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
    private static Path tool(String command) throws IOException {
        var path = Path.of(command);
        if (!path.isAbsolute()) for (String directory : System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator)) {
            var candidate = Path.of(directory).resolve(command); if (Files.isExecutable(candidate)) return candidate.toRealPath();
        }
        require(Files.isExecutable(path), "Missing vector inspection tool: " + command); return path.toRealPath();
    }
    private static Map<String,Object> identity(Path path) throws Exception { return Map.of("path", path.toString(), "sha256", digest(Files.readAllBytes(path))); }
    private static String digest(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
}
