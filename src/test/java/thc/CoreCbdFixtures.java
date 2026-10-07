// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import thc.runtime.TargetLayout;
import thc.runtime.CoreFloatingLiteral;

/** Test models use the Haskell CBD encoder; executable inputs are always CBD. */
@SuppressWarnings("unchecked")
public final class CoreCbdFixtures {
    private CoreCbdFixtures() {}
    public static Path write(Path output, Map<String,Object> module) throws Exception {
        return CoreCbdTestSupport.writeModel(output, (Map<String,Object>) inspection(module, true));
    }
    public static Map<String,Object> module(Path output, Map<String,Object> value) throws Exception {
        write(output, value);
        String hash = hash(Files.readAllBytes(output));
        return CoreFormatTestSupport.map("name", value.get("module"), "boundary", value.get("boundary"), "sha256", hash,
            "compact", Map.of("path", output.toString(), "sha256", hash, "format", CoreCompactFormat.NAME),
            "containsDelimitedControl", thc.runtime.DelimitedControl.INSTANCE.contains(value.get("bindings")),
            "registrationObligations", CoreForeignArtifacts.hasRegistrationObligations(value),
            "mainAlias", ((List<Map<String,Object>>) value.get("bindings")).stream().anyMatch(b -> CoreUnitDirectory.MAIN_ALIAS.equals(b.get("id"))),
            "packageScalarDeclarations", PackageFinalizers.hasDeclarations(value));
    }
    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    public static Object snapshot(Object value) {
        if (value instanceof Map<?,?> fields) {
            var copy = new LinkedHashMap<String,Object>();
            fields.forEach((key, child) -> { if (!key.equals("compactOrigin")) copy.put((String) key, snapshot(child)); });
            return copy;
        }
        if (value instanceof List<?> fields) return new ArrayList<>(fields.stream().map(CoreCbdFixtures::snapshot).toList());
        return value;
    }
    private static Object inspection(Object value, boolean model) {
        if (value instanceof CoreFloatingLiteral literal) return literal.document();
        if (value instanceof Map<?,?> fields) {
            var copy = new LinkedHashMap<String,Object>();
            fields.forEach((key, child) -> copy.put((String) key, inspection(child, model)));
            return copy;
        }
        if (value instanceof List<?> fields) {
            var copy = new ArrayList<>(fields.stream().map(child -> inspection(child, model)).toList());
            if (model && fields.size() > 2 && "lit".equals(fields.getFirst())) {
                if ("float".equals(fields.get(1)) && fields.get(2) instanceof CoreFloatingLiteral.Single literal) {
                    copy.set(1, "float-bits"); copy.set(2, Integer.toUnsignedString(literal.bits()));
                } else if ("double".equals(fields.get(1)) && fields.get(2) instanceof CoreFloatingLiteral.Double literal) {
                    copy.set(1, "double-bits"); copy.set(2, Long.toUnsignedString(literal.bits()));
                }
            }
            return copy;
        }
        return value;
    }
    public static Map<String,Object> read(Path path) throws Exception {
        try (var file = new CoreCompactFile(path, hash(Files.readAllBytes(path)), true)) {
            var records = new CoreCompactRecords(file, path.toString());
            var module = new LinkedHashMap<>(records.header());
            var bindings = new ArrayList<Map<String,Object>>();
            file.verifyBindingOffsets(offset -> bindings.add(records.binding(offset)));
            module.put("bindings", bindings);
            return (Map<String,Object>) snapshot(module);
        } catch (Throwable failure) { return rethrow(failure); }
    }
    /** Select genuine roots after the caller verifies the complete fixture inventory. */
    public static Map<String,Object> selectedRoots(List<String> paths, String entry, String secondRoot) {
        var request = (Map<String,Object>) Json.parse(CoreModules.request(paths, entry, false, false,
            "ast", false, true, secondRoot, null, false));
        var selected = CoreModules.selectedModules(request, entry);
        var merger = new CoreModules.Merger();
        for (var module : (List<Map<String,Object>>) selected.get("modules")) merger.addDetached(module);
        var linked = merger.finish();
        linked.put("foreignExceptionBridgeUnit", selected.get("foreignExceptionBridgeUnit"));
        return linked;
    }
    public record Corpus(TargetLayout targetLayout, List<Map<String,Object>> modules) {
        public TargetLayout getTargetLayout() { return targetLayout; }
    }
    public static void visitRequest(Map<String,Object> input, Consumer<Map<String,Object>> accept) {
        var directory = CoreModules.unitDirectory(input);
        try (var sources = directory.open(Boolean.TRUE.equals(input.get("verifyArtifacts")), false)) {
            for (var module : directory.getModules()) {
                var decoded = read(module.artifact().path());
                decoded.put("foreignExceptionBridgeUnit", directory.getForeignExceptionBridgeUnit());
                accept.accept(decoded);
            }
            CoreModules.visitUnitConsumers(input, sources, module -> accept.accept((Map<String,Object>) snapshot(module)));
        } catch (Exception failure) { rethrow(failure); }
    }
    /** Explicit CBD inspection for internal Java fixture models, not a runtime input route. */
    public static Corpus visitModules(String manifest, BiConsumer<Map<String,Object>,String> accept) throws Exception {
        var directory = CoreUnitDirectory.read((Map<?,?>) Json.parse(Files.readString(Path.of(manifest))));
        var modules = new ArrayList<Map<String,Object>>();
        for (var record : directory.getModules()) {
            var module = read(record.artifact().path());
            modules.add(module); accept.accept(module, Json.stringify(inspection(module, false)));
        }
        return new Corpus(directory.getTargetLayout(), modules);
    }
    public static TargetLayout appendModules(StringBuilder output, String manifest) throws Exception {
        var first = new boolean[]{output.isEmpty() || output.toString().equals("[")};
        return visitModules(manifest, (module, text) -> {
            if (!first[0]) output.append(','); first[0] = false; output.append(text);
        }).targetLayout();
    }
    @SuppressWarnings("unchecked") private static <T,E extends Throwable> T rethrow(Throwable failure) throws E { throw (E) failure; }
}
