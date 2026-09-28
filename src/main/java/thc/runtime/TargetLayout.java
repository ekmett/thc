// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import thc.Json;

/** Target-derived ABI of the original GHC stack and InfoProv sources. */
public final class TargetLayout {
    private final String compilerId, compilerAbi, platform, way, endianness;
    private final int wordBytes;
    private final boolean tablesNextToCode;
    private final Map<String, Integer> values;

    private TargetLayout(String compilerId, String compilerAbi, String platform, String way,
                         int wordBytes, String endianness, boolean tablesNextToCode, Map<String, Integer> values) {
        this.compilerId = compilerId; this.compilerAbi = compilerAbi; this.platform = platform; this.way = way;
        this.wordBytes = wordBytes; this.endianness = endianness; this.tablesNextToCode = tablesNextToCode;
        this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
    public String getCompilerId() { return compilerId; }
    public String getCompilerAbi() { return compilerAbi; }
    public String getPlatform() { return platform; }
    public String getWay() { return way; }
    public int getWordBytes() { return wordBytes; }
    public String getEndianness() { return endianness; }
    public boolean getTablesNextToCode() { return tablesNextToCode; }
    public int offset(String name) {
        Integer result = values.get(name);
        if (result == null) throw new IllegalStateException("Unknown GHC target layout field: " + name);
        return result;
    }
    @Override public boolean equals(Object other) {
        return other instanceof TargetLayout t && compilerId.equals(t.compilerId) && compilerAbi.equals(t.compilerAbi) &&
            platform.equals(t.platform) && way.equals(t.way) && wordBytes == t.wordBytes &&
            endianness.equals(t.endianness) && tablesNextToCode == t.tablesNextToCode && values.equals(t.values);
    }
    @Override public int hashCode() {
        return Objects.hash(compilerId, compilerAbi, platform, way, wordBytes, endianness, tablesNextToCode, values);
    }
    public Map<String, Object> document() {
        Map<String, Object> compiler = new LinkedHashMap<>();
        compiler.put("id", compilerId); compiler.put("abi", compilerAbi); compiler.put("platform", platform); compiler.put("way", way);
        Map<String, Object> layout = new LinkedHashMap<>(values);
        layout.put("schema", 1); layout.put("profiled", false); layout.put("wordBytes", wordBytes);
        layout.put("endianness", endianness); layout.put("tablesNextToCode", tablesNextToCode); layout.put("targetPlatform", platform);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("format", "thc-target-layout"); result.put("schema", 1); result.put("compiler", compiler); result.put("layout", layout);
        return result;
    }

    // Exact Wired.moduleSources HSC inventory; receipts are checked against its producing catalog.
    private static final Set<String> GENERATED_SOURCES = Set.of(
        "GHC/Internal/Heap/Constants.hsc", "GHC/Internal/Heap/InfoTable/Types.hsc",
        "GHC/Internal/Heap/InfoTable.hsc", "GHC/Internal/Stack/Constants.hsc", "GHC/Internal/InfoProv/Types.hsc",
        "GHC/Internal/Stack/CCS.hsc", "GHC/Internal/ExecutionStack/Internal.hsc");
    private static Set<String> windowsGeneratedSources;
    private static synchronized Set<String> windowsGeneratedSources() {
        if (windowsGeneratedSources != null) return windowsGeneratedSources;
        Map<?, ?> catalog;
        try (var input = TargetLayout.class.getResourceAsStream("/thc/windows-ghc-internal.json")) {
            if (input == null) throw new IllegalStateException("Missing pinned Windows GHC source catalog");
            catalog = (Map<?, ?>) Json.parse(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException failure) { throw propagate(failure); }
        require(Objects.equals(catalog.get("schema"), 1L) && Objects.equals(catalog.get("ghc"), "9.14.1"),
            "Invalid Windows GHC source catalog");
        List<String> paths = new ArrayList<>();
        for (Object item : (List<?>) catalog.get("files")) {
            String path = (String) ((Map<?, ?>) item).get("path");
            if (path.endsWith(".hsc")) paths.add(path);
        }
        Set<String> unique = new LinkedHashSet<>(paths);
        require(!paths.isEmpty() && unique.size() == paths.size(), "Invalid Windows HSC inventory");
        windowsGeneratedSources = unique;
        return unique;
    }
    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException propagate(Throwable failure) throws E { throw (E) failure; }

    private static final List<String> NUMBERS = List.of(
        "infoTableBytes", "infoTablePtrsOffset", "infoTablePtrsBytes", "infoTableNptrsOffset", "infoTableNptrsBytes",
        "infoTableTypeOffset", "infoTableTypeBytes", "infoTableSrtOffset", "infoTableSrtBytes",
        "infoProvEntBytes", "infoProvBytes", "infoProvEntInfoOffset", "infoProvEntProvOffset", "infoProvNameOffset",
        "infoProvDescOffset", "infoProvDescBytes", "infoProvTyDescOffset", "infoProvLabelOffset", "infoProvUnitOffset",
        "infoProvModuleOffset", "infoProvFileOffset", "infoProvSpanOffset", "closureRetBco", "closureRetSmall",
        "closureRetBig", "closureRetFun", "closureUpdateFrame", "closureCatchFrame", "closureUnderflowFrame",
        "closureStopFrame", "closureStack", "closureAtomicallyFrame", "closureCatchRetryFrame", "closureCatchStmFrame",
        "closureAnnFrame", "stackHeaderBytes", "stackCatchHandlerBytes", "stackCatchFrameBytes", "stackCatchStmCodeBytes",
        "stackCatchStmHandlerBytes", "stackCatchStmFrameBytes", "stackUpdateeBytes", "stackUpdateFrameBytes",
        "stackAtomicallyCodeBytes", "stackAtomicallyResultBytes", "stackAtomicallyFrameBytes", "stackCatchRetryAltCodeBytes",
        "stackCatchRetryFirstCodeBytes", "stackCatchRetryAltBytes", "stackCatchRetryFrameBytes", "stackRetFunSizeBytes",
        "stackRetFunFunBytes", "stackRetFunPayloadBytes", "stackRetFunFrameBytes", "stackAnnPayloadBytes",
        "stackAnnFrameBytes", "stackClosurePayloadBytes");
    private static final Map<String, Integer> ORDINALS = Map.ofEntries(
        Map.entry("closureRetBco", 29), Map.entry("closureRetSmall", 30), Map.entry("closureRetBig", 31),
        Map.entry("closureRetFun", 32), Map.entry("closureUpdateFrame", 33), Map.entry("closureCatchFrame", 34),
        Map.entry("closureUnderflowFrame", 35), Map.entry("closureStopFrame", 36), Map.entry("closureStack", 53),
        Map.entry("closureAtomicallyFrame", 55), Map.entry("closureCatchRetryFrame", 56), Map.entry("closureCatchStmFrame", 57),
        Map.entry("closureAnnFrame", 65));
    private static void require(boolean valid, String message) { if (!valid) throw new IllegalArgumentException(message); }
    private static String hostPlatform() {
        String architecture = System.getProperty("os.arch");
        String arch = switch (architecture.toLowerCase(Locale.ROOT)) {
            case "aarch64", "arm64" -> "aarch64";
            case "x86_64", "amd64" -> "x86_64";
            default -> throw new IllegalStateException("Unsupported GHC target architecture: " + architecture);
        };
        String name = System.getProperty("os.name");
        String os;
        if (name.startsWith("Mac")) os = "osx";
        else if (name.startsWith("Linux")) os = "linux";
        else if (name.startsWith("Windows")) os = "windows";
        else throw new IllegalStateException("Unsupported GHC target OS: " + name);
        return arch + "-" + os;
    }
    private static int integer(Object value, String field) {
        require(value instanceof Integer || value instanceof Long, "Invalid GHC target layout field: " + field);
        long number = ((Number) value).longValue();
        require(number >= 0 && number <= Integer.MAX_VALUE, "Out-of-range GHC target layout field: " + field);
        return (int) number;
    }
    private static void field(Map<String, Integer> values, String struct, String offset, int width) {
        require(width > 0 && values.get(offset) <= values.get(struct) - width, "GHC target layout " + offset + " exceeds " + struct);
    }
    private static TargetLayout fromParts(Map<?, ?> compiler, Map<?, ?> layout) {
        require(compiler.keySet().equals(Set.of("id", "abi", "platform", "way")), "Incomplete GHC target compiler identity");
        String id = compiler.get("id") instanceof String s ? s : null;
        String abi = compiler.get("abi") instanceof String s ? s : null;
        String platform = compiler.get("platform") instanceof String s ? s : null;
        String way = compiler.get("way") instanceof String s ? s : null;
        require("ghc-9.14.1".equals(id) && abi != null && abi.matches("[A-Za-z0-9._+-]+") &&
            Objects.equals(platform, hostPlatform()) &&
            Objects.equals(way, platform.endsWith("-windows") ? "vanilla-nonprofiling" : "dynamic-nonprofiling"),
            "GHC target identity or way differs from this runtime");
        Set<String> keys = new HashSet<>(NUMBERS);
        keys.addAll(Set.of("schema", "profiled", "wordBytes", "endianness", "targetPlatform", "tablesNextToCode"));
        require(layout.keySet().equals(keys) && integer(layout.get("schema"), "schema") == 1 &&
            Boolean.FALSE.equals(layout.get("profiled")) && Objects.equals(layout.get("targetPlatform"), platform),
            "Incomplete or profiled GHC target layout");
        if (!(layout.get("tablesNextToCode") instanceof Boolean tablesNextToCode))
            throw new IllegalStateException("Missing GHC tables-next-to-code setting");
        int word = integer(layout.get("wordBytes"), "wordBytes");
        String endian = layout.get("endianness") instanceof String s ? s : null;
        String nativeEndian = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN ? "little" : "big";
        require(word == Long.BYTES && nativeEndian.equals(endian), "GHC word width or endianness differs from this runtime");
        Map<String, Integer> values = new LinkedHashMap<>();
        for (String name : NUMBERS) values.put(name, integer(layout.get(name), name));
        for (String name : List.of("Ptrs", "Nptrs", "Type", "Srt"))
            field(values, "infoTableBytes", "infoTable" + name + "Offset", values.get("infoTable" + name + "Bytes"));
        field(values, "infoProvEntBytes", "infoProvEntInfoOffset", word);
        field(values, "infoProvEntBytes", "infoProvEntProvOffset", values.get("infoProvBytes"));
        for (String name : List.of("Name", "TyDesc", "Label", "Unit", "Module", "File", "Span"))
            field(values, "infoProvBytes", "infoProv" + name + "Offset", word);
        field(values, "infoProvBytes", "infoProvDescOffset", values.get("infoProvDescBytes"));
        String[][] frames = {
            {"stackCatchFrameBytes", "stackCatchHandlerBytes"},
            {"stackCatchStmFrameBytes", "stackCatchStmCodeBytes", "stackCatchStmHandlerBytes"},
            {"stackUpdateFrameBytes", "stackUpdateeBytes"},
            {"stackAtomicallyFrameBytes", "stackAtomicallyCodeBytes", "stackAtomicallyResultBytes"},
            {"stackCatchRetryFrameBytes", "stackCatchRetryAltCodeBytes", "stackCatchRetryFirstCodeBytes", "stackCatchRetryAltBytes"},
            {"stackAnnFrameBytes", "stackAnnPayloadBytes"},
            {"stackRetFunFrameBytes", "stackRetFunSizeBytes", "stackRetFunFunBytes"}};
        for (String[] frame : frames) {
            require(values.get(frame[0]) >= values.get("stackHeaderBytes"), "Invalid GHC stack frame size: " + frame[0]);
            for (int i = 1; i < frame.length; i++) field(values, frame[0], frame[i], word);
        }
        require(values.get("stackRetFunPayloadBytes") <= values.get("stackRetFunFrameBytes") &&
            values.get("stackClosurePayloadBytes") >= values.get("stackHeaderBytes"), "Invalid GHC stack payload offset");
        for (var entry : ORDINALS.entrySet()) require(values.get(entry.getKey()).equals(entry.getValue()), "GHC closure ordinals differ from 9.14.1");
        return new TargetLayout(id, abi, platform, way, word, endian, tablesNextToCode, values);
    }
    public static TargetLayout fromDocument(Object value) {
        if (!(value instanceof Map<?, ?> record)) throw new IllegalStateException("Invalid target layout document");
        require(record.keySet().equals(Set.of("format", "schema", "compiler", "layout")) &&
            Objects.equals(record.get("format"), "thc-target-layout") && integer(record.get("schema"), "schema") == 1,
            "Invalid target layout document");
        if (!(record.get("compiler") instanceof Map<?, ?> compiler)) throw new IllegalStateException("Missing target compiler");
        if (!(record.get("layout") instanceof Map<?, ?> layout)) throw new IllegalStateException("Missing target layout");
        return fromParts(compiler, layout);
    }
    public static TargetLayout fromReceipts(Map<?, ?> index, Map<?, ?> inputs) {
        Object layout = index.get("targetLayout"), receipt = inputs.get("targetLayout");
        if (layout == null && receipt == null) return null;
        require(layout != null && layout.equals(receipt) && Objects.equals(index.get("generatedSources"), inputs.get("generatedSources")),
            "GHC target layout receipts differ");
        if (!(inputs.get("compiler") instanceof Map<?, ?> compiler)) throw new IllegalStateException("Missing target compiler");
        if (!(layout instanceof Map<?, ?> fields)) throw new IllegalStateException("Invalid GHC target layout");
        TargetLayout target = fromParts(compiler, fields);
        if (inputs.get("component") instanceof Map<?, ?> component && "installed-interface".equals(component.get("kind"))) {
            Object items = inputs.get("recipeArtifacts");
            Object item = items instanceof List<?> list && list.size() == 1 ? list.getFirst() : null;
            require(index.get("generatedSources") == null && inputs.get("rtsRegistration") instanceof String registration &&
                !blank(registration) && item instanceof Map<?, ?> recipe && recipe.keySet().equals(Set.of("path", "sha256")) &&
                "src/driver/cbits/target-layout.c".equals(recipe.get("path")) && recipe.get("sha256") instanceof String sha && sha.matches("[0-9a-f]{64}"),
                "Invalid selected-GHC layout provenance");
        } else {
            Set<String> expected = target.platform.equals("x86_64-windows") ? windowsGeneratedSources() : GENERATED_SOURCES;
            if (!(index.get("generatedSources") instanceof List<?> generated)) throw new IllegalStateException("Missing generated GHC source receipts");
            boolean valid = generated.size() == expected.size();
            Set<String> found = new HashSet<>();
            for (Object item : generated) {
                if (!(item instanceof Map<?, ?> source) || !source.keySet().equals(Set.of("path", "sha256")) ||
                    !(source.get("path") instanceof String path) || !expected.contains(path) ||
                    !(source.get("sha256") instanceof String sha) || !sha.matches("[0-9a-f]{64}")) { valid = false; break; }
                found.add(path);
            }
            require(valid && found.equals(expected), "Invalid generated GHC source receipts");
        }
        return target;
    }
    private static boolean blank(String value) {
        for (int i = 0; i < value.length(); i++)
            if (!Character.isWhitespace(value.charAt(i)) && !Character.isSpaceChar(value.charAt(i))) return false;
        return true;
    }
}
