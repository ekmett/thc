// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import thc.Json;

/** Hosted Linux deployment selection. No native constructor runs during capture. */
@SuppressWarnings("unchecked")
public final class NativeLibraryCapture {
    // These libraries implement the image host's glibc ABI. Bundling another libc
    // into the JVM process is not a supported way to change that ABI.
    private static final Set<String> SYSTEM = Set.of("ld-linux-x86-64.so.2", "libc.so.6",
        "libm.so.6", "libdl.so.2", "libpthread.so.0", "librt.so.1", "libresolv.so.2",
        "libutil.so.1", "libanl.so.1");
    private static final Pattern RESOLVED = Pattern.compile("^\\s*(\\S+) => (/.+) \\(0x[0-9a-fA-F]+\\)\\s*$");
    private final Path receipt;
    private final String readobj = System.getenv().getOrDefault("THC_LLVM_READOBJ", "llvm-readobj");
    private final Map<String, Map<String,Object>> providers = new LinkedHashMap<>();
    private final Map<String, Map<String,Object>> companions = new LinkedHashMap<>();
    private final List<Object> observations = new ArrayList<>();
    private final Map<String,Object> tools = new LinkedHashMap<>(), systemProviders = new LinkedHashMap<>();
    private Path temporary;
    private String loader;

    public NativeLibraryCapture(Path receipt) { this.receipt = receipt; }

    /** Add only deployment data to already validated, detached module metadata. */
    public Map<String,Object> capture(Map<String,Object> input) {
        try {
            temporary = Files.createTempDirectory("thc-native-capture-");
            var modules = new ArrayList<Map<String,Object>>();
            for (var original : (List<Map<String,Object>>) input.get("modules")) {
                var module = new LinkedHashMap<>(original);
                if (module.get("packageNativeLink") instanceof Map<?,?> link)
                    module.put("packageNativeLink", component((Map<String,Object>) link));
                modules.add(module);
            }
            var result = new LinkedHashMap<>(input); result.put("modules", List.copyOf(modules));
            Files.writeString(receipt, Json.stringify(Map.of("selection", "image-host-dynamic-loader",
                "systemLibraries", SYSTEM.stream().sorted().toList(), "providers", observations,
                "tools", tools, "systemProviders", systemProviders, "ldLibraryPath", System.getenv().getOrDefault("LD_LIBRARY_PATH", ""))) + "\n");
            return java.util.Collections.unmodifiableMap(result);
        } catch (IOException error) { throw new IllegalStateException("Cannot capture native library deployment", error); }
        finally {
            if (temporary != null) try (var files = Files.list(temporary)) {
                for (var file : files.toList()) Files.delete(file);
                Files.delete(temporary);
            } catch (IOException error) { throw new IllegalStateException("Cannot remove native capture staging", error); }
        }
    }

    private Map<String,Object> component(Map<String,Object> original) throws IOException {
        var result = new LinkedHashMap<>(original);
        if (original.get("nativeLibrary") instanceof Map<?,?> nativeLibrary) {
            if (!System.getProperty("os.name").equals("Linux") || !System.getProperty("os.arch").equals("amd64"))
                throw new IllegalArgumentException("Native dependency capture requires Linux x86-64");
            var library = (Map<String,Object>) nativeLibrary;
            String hash = (String) library.get("sha256");
            var captured = companions.get(hash);
            if (captured == null) {
                if (library.containsKey("bundledLibraries"))
                    throw new IllegalArgumentException("Native dependency capture requires original companion metadata");
                byte[] bytes = HexFormat.of().parseHex((String) library.get("hex"));
                require(digest(bytes).equals(hash), "Native companion digest differs");
                var file = temporary.resolve("companion-" + hash + ".so"); Files.write(file, bytes);
                var elf = inspect(file);
                for (var tag : (List<Map<String,Object>>) elf.get("DynamicSection"))
                    if (Set.of("RPATH", "RUNPATH").contains(tag.get("Type")))
                        for (Object path : (List<?>) tag.get("Path"))
                            require(!path.toString().contains("$ORIGIN") && !path.toString().contains("${ORIGIN}"),
                                "Native companion requires its original $ORIGIN directory");
                var selected = resolve(file);
                var ordered = new LinkedHashMap<String,Map<String,Object>>();
                for (String name : needed(elf)) visit(name, selected, ordered, new LinkedHashSet<>());
                captured = new LinkedHashMap<>(library);
                captured.put("bundledLibraries", List.copyOf(ordered.values()));
                companions.put(hash, captured);
            }
            result.put("nativeLibrary", captured);
        }
        if (original.get("dependencies") instanceof List<?> dependencies) {
            var captured = new ArrayList<Map<String,Object>>();
            for (Object dependency : dependencies) captured.add(component((Map<String,Object>) dependency));
            result.put("dependencies", List.copyOf(captured));
        }
        return java.util.Collections.unmodifiableMap(result);
    }

    private void visit(String name, Map<String,Path> selected, Map<String,Map<String,Object>> ordered,
            Set<String> visiting) throws IOException {
        require(name.matches("[A-Za-z0-9_+.-]+") && !name.equals(".") && !name.equals(".."),
            "Path-bearing native dependency is unsupported: " + name);
        Path source = selected.get(name);
        require(source != null, "Unresolved native dependency: " + name);
        if (SYSTEM.contains(name)) return;
        require(visiting.add(name), "Cyclic native dependency is unsupported: " + name);
        byte[] bytes = Files.readAllBytes(source); String hash = digest(bytes);
        var old = providers.get(name);
        require(old == null || old.get("sha256").equals(hash), "Conflicting native provider: " + name);
        if (ordered.containsKey(name)) { visiting.remove(name); return; }
        var staged = temporary.resolve("provider-" + hash + ".so"); Files.write(staged, bytes);
        var elf = inspect(staged);
        var summary = (Map<String,Object>) elf.get("FileSummary");
        require(name.equals(summary.get("LoadName")), "Native provider SONAME differs: " + source);
        for (String dependency : needed(elf)) visit(dependency, selected, ordered, visiting);
        visiting.remove(name);
        var captured = old != null ? old : Map.<String,Object>of("name", name, "sha256", hash,
            "hex", HexFormat.of().formatHex(bytes));
        providers.putIfAbsent(name, captured); ordered.putIfAbsent(name, captured);
        if (old == null) observations.add(Map.of("name", name, "path", source.toString(), "sha256", hash,
            "needed", needed(elf)));
        require(digest(Files.readAllBytes(source)).equals(hash), "Native provider changed during capture: " + source);
    }

    private Map<String,Object> inspect(Path file) throws IOException {
        var result = (List<Map<String,Object>>) Json.parse(command(readobj, "--dynamic-table", "--elf-output-style=JSON", file.toString()));
        require(result.size() == 1, "Expected one native ELF");
        var elf = result.getFirst(); var summary = (Map<String,Object>) elf.get("FileSummary");
        require("elf64-x86-64".equals(summary.get("Format")), "Native provider requires Linux x86-64 ELF");
        for (var tag : (List<Map<String,Object>>) elf.get("DynamicSection"))
            require(!Set.of("AUDIT", "DEPAUDIT", "FILTER", "AUXILIARY").contains(tag.get("Type")),
                "Native loader indirection is unsupported: " + tag.get("Type"));
        return elf;
    }

    private static List<String> needed(Map<String,Object> elf) {
        var names = new ArrayList<String>();
        for (var tag : (List<Map<String,Object>>) elf.get("DynamicSection"))
            if ("NEEDED".equals(tag.get("Type"))) names.add((String) tag.get("Library"));
        return names;
    }

    private String loader() throws IOException {
        if (loader != null) return loader;
        var executable = Path.of(System.getProperty("java.home"), "bin", "java").toRealPath();
        var records = (List<Map<String,Object>>) Json.parse(command(readobj, "--program-headers", "--elf-output-style=JSON", executable.toString()));
        require(records.size() == 1, "Expected one image host executable");
        byte[] bytes = Files.readAllBytes(executable);
        tools.put("image-host-java", Map.of("path", executable.toString(), "sha256", digest(bytes)));
        for (var entry : (List<Map<String,Object>>) records.getFirst().get("ProgramHeaders")) {
            var header = (Map<String,Object>) entry.get("ProgramHeader");
            if (!"PT_INTERP".equals(((Map<?,?>) header.get("Type")).get("Name"))) continue;
            int offset = Math.toIntExact(((Number) header.get("Offset")).longValue());
            int size = Math.toIntExact(((Number) header.get("FileSize")).longValue());
            require(offset >= 0 && size > 1 && (long) offset + size <= bytes.length && bytes[offset + size - 1] == 0,
                "Invalid image host interpreter");
            var path = Path.of(new String(bytes, offset, size - 1, java.nio.charset.StandardCharsets.UTF_8));
            require(path.isAbsolute() && path.getFileName().toString().equals("ld-linux-x86-64.so.2"),
                "Native capture requires the Linux x86-64 glibc loader");
            loader = path.toRealPath().toString(); return loader;
        }
        throw new IllegalArgumentException("Image host has no ELF interpreter");
    }

    private Map<String,Path> resolve(Path file) throws IOException {
        var result = new LinkedHashMap<String,Path>();
        for (String line : command(loader(), "--list", file.toString()).split("\\R")) {
            require(!line.contains("=> not found"), "Unresolved native dependency: " + line.strip());
            var match = RESOLVED.matcher(line);
            if (match.matches()) result.put(match.group(1), Path.of(match.group(2)).toRealPath());
            else if (line.strip().startsWith("/")) {
                String path = line.strip().split(" \\(", 2)[0];
                result.put(Path.of(path).getFileName().toString(), Path.of(path).toRealPath());
            }
        }
        for (var entry : result.entrySet()) if (SYSTEM.contains(entry.getKey())) {
            var observation = Map.of("path", entry.getValue().toString(), "sha256", digest(Files.readAllBytes(entry.getValue())));
            var old = systemProviders.putIfAbsent(entry.getKey(), observation);
            require(old == null || old.equals(observation), "Conflicting system provider: " + entry.getKey());
        }
        return result;
    }

    private String command(String... arguments) throws IOException {
        String requested = arguments[0];
        Path tool = Path.of(requested);
        if (!tool.isAbsolute()) {
            tool = java.util.Arrays.stream(System.getenv().getOrDefault("PATH", "").split(":"))
                .map(directory -> Path.of(directory).resolve(requested)).filter(Files::isExecutable).findFirst()
                .orElseThrow(() -> new IOException("Missing native inspection tool: " + requested));
        }
        tool = tool.toRealPath();
        var observation = Map.of("path", tool.toString(), "sha256", digest(Files.readAllBytes(tool)));
        var previous = tools.putIfAbsent(arguments[0], observation);
        require(previous == null || previous.equals(observation), "Native inspection tool changed: " + arguments[0]);
        arguments = arguments.clone(); arguments[0] = tool.toString();
        var output = temporary.resolve("command.log");
        var builder = new ProcessBuilder(arguments).redirectErrorStream(true).redirectOutput(output.toFile());
        builder.environment().put("LC_ALL", "C");
        builder.environment().remove("LD_PRELOAD"); builder.environment().remove("LD_AUDIT");
        var process = builder.start();
        try {
            require(process.waitFor(15, TimeUnit.SECONDS), "Native inspection timed out: " + arguments[0]);
            String text = Files.readString(output);
            require(process.exitValue() == 0, "Native inspection failed: " + String.join(" ", arguments) + "\n" + text);
            return text;
        } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException(error); }
        finally {
            boolean interrupted = Thread.interrupted();
            if (process.isAlive()) process.destroyForcibly();
            while (process.isAlive()) try { process.waitFor(); }
                catch (InterruptedException error) { interrupted = true; process.destroyForcibly(); }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException error) { throw new AssertionError(error); }
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
