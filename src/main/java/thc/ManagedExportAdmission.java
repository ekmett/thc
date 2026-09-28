// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.util.*;
import java.util.function.Function;

/** A ticket authorizes only the immutable module object that was checked. */
public final class ManagedExportAdmission {
    private final Map<String,Object> module;
    private final List<ManagedExportSignature> exports;
    private ManagedExportAdmission(Map<String,Object> module, List<ManagedExportSignature> exports) { this.module = module; this.exports = exports; }
    public Map<String,Object> getModule() { return module; }
    public List<ManagedExportSignature> getExports() { return exports; }
    public static ManagedExportAdmission read(Map<String,Object> module) { return read(module, null); }
    @SuppressWarnings("unchecked")
    public static ManagedExportAdmission read(Map<String,Object> module, Function<String,Map<String,Object>> binding) {
        CoreForeignArtifacts.validateArchive(module, binding == null);
        require(Objects.equals(module.get("schema"), 2L) && !module.containsKey("foreignLink"), "Managed exports require original, unlinked static-export products");
        String unit = text(module.get("unit")), name = text(module.get("module"));
        var inventory = record(module.get("staticForeignExports"), "schema producer scope execution unit module exports");
        require(Objects.equals(inventory.get("schema"), 1L) && Objects.equals(inventory.get("producer"), "THC.Plugin/typeCheckResultAction") && Objects.equals(inventory.get("scope"), "static-export-associations") && Objects.equals(inventory.get("execution"), "not-linked") && Objects.equals(inventory.get("unit"), unit) && Objects.equals(inventory.get("module"), name), "Invalid managed export inventory owner/schema");
        var provenance = record(module.get("staticForeignExportRegistration"), "schema scope execution profile status roots wordBits expectedForeign expectedExports");
        require(Objects.equals(provenance.get("schema"), 2L) && Objects.equals(provenance.get("scope"), "retained-foreign-products") && Objects.equals(provenance.get("execution"), "not-linked") && Objects.equals(provenance.get("status"), "verified") && Arrays.asList("ghc-9.14.1-thc-only-native-static-ccall-v1", "ghc-9.14.1-thc-only-native-static-ccall-imports-v2").contains(provenance.get("profile")) && Objects.equals(provenance.get("wordBits"), 64L), "Managed exports require verified retained static-export registration");
        require(Objects.equals(provenance.get("expectedForeign"), module.get("foreign")), "Managed export foreign product changed after verification");
        require(Objects.equals(provenance.get("expectedExports"), inventory), "Managed export inventory changed after verification");
        var foreign = (Map<String,Object>) module.get("foreign");
        if (!(foreign.get("stubs") instanceof Map<?,?> stubs)) throw new IllegalStateException("Managed export registration lacks original stubs");
        require(Objects.equals(foreign.get("files"), List.of()) && Objects.equals(stubs.get("finalizers"), List.of()), "Additional foreign files/finalizers are not managed export registration");
        var initializers = (List<Map<String,Object>>) stubs.get("initializers");
        require(initializers.size() == 1 && Objects.equals(initializers.getFirst().get("unit"), unit) && Objects.equals(initializers.getFirst().get("module"), name), "Managed export registration owner changed");
        if (!(inventory.get("exports") instanceof List<?> originals)) throw new IllegalStateException("Missing static export declarations");
        require(!originals.isEmpty(), "No declared static exports");
        var names = new HashSet<String>(); var roots = new ArrayList<Map<String,Object>>();
        if (!(module.get("bindings") instanceof List<?>)) throw new IllegalStateException("Missing exported Core bindings");
        var bindings = (List<Map<String,Object>>) module.get("bindings");
        var exports = new ArrayList<ManagedExportSignature>();
        for (Object original : originals) {
            var export = record(original, "binder symbol convention declaredType normalizedType normalizationRole arguments result effect");
            var binder = identity(export.get("binder"));
            require(Objects.equals(binder.get("unit"), unit) && Objects.equals(binder.get("module"), name) && Objects.equals(binder.get("namespace"), "value"), "Static export binder has a different owner");
            roots.add(binder); String id = unit + ":" + name + "." + binder.get("occurrence");
            require(binding == null ? bindings.stream().filter(value -> Objects.equals(value.get("id"), id)).count() == 1 : Objects.equals(binding.apply(id).get("id"), id), "Static export does not resolve to one exact Core binder: " + id);
            String symbol = text(export.get("symbol")); require(names.add(symbol), "Duplicate static export symbol: " + unit + ":" + name + "/" + symbol);
            require(Objects.equals(export.get("convention"), "ccall") && Objects.equals(export.get("normalizationRole"), "representational"), "Unsupported static export convention/normalization");
            type(export.get("declaredType")); var remaining = type(export.get("normalizedType"));
            var arguments = new ArrayList<Map<String,Object>>();
            while (Objects.equals(remaining.get("kind"), "function")) {
                var multiplicity = (Map<String,Object>) remaining.get("multiplicity");
                require(nominal(multiplicity, "GHC.Internal.Types", "Many", "data", 0), "Managed export requires unrestricted arguments");
                arguments.add((Map<String,Object>) remaining.get("argument")); remaining = (Map<String,Object>) remaining.get("result");
            }
            boolean io = Objects.equals(export.get("effect"), "io"); require(io || Objects.equals(export.get("effect"), "pure"), "Unknown static export effect");
            if (io) {
                require(nominal(remaining, "GHC.Internal.Types", "IO", "type", 1), "IO export normalized type is not IO");
                remaining = ((List<Map<String,Object>>) remaining.get("arguments")).getFirst();
            } else require(!nominal(remaining, "GHC.Internal.Types", "IO", "type", 1), "IO export cannot claim a pure effect");
            require(arguments.equals(export.get("arguments")) && remaining.equals(export.get("result")), "Static export signature projections disagree");
            exports.add(new ManagedExportSignature(unit, name, symbol, id, arguments, remaining, io, 64));
        }
        require(Objects.equals(provenance.get("roots"), roots), "Static export registration roots differ from declarations");
        return new ManagedExportAdmission(module, exports);
    }
    private static void require(boolean value, String message) { if (!value) throw new IllegalArgumentException(message); }
    private static String text(Object value) { if (value instanceof String text && !text.isEmpty() && text.indexOf(0) < 0) return text; throw new IllegalStateException("Invalid static export text"); }
    @SuppressWarnings("unchecked")
    private static Map<String,Object> record(Object value, String keys) {
        require(value instanceof Map<?,?> fields && fields.keySet().equals(Set.of(keys.split(" "))), "Missing or invalid static export metadata record"); return (Map<String,Object>) value;
    }
    private static Map<String,Object> identity(Object value) { var fields = record(value, "unit module occurrence namespace"); fields.values().forEach(ManagedExportAdmission::text); return fields; }
    @SuppressWarnings("unchecked")
    private static Map<String,Object> type(Object value) {
        if (!(value instanceof Map<?,?>)) throw new IllegalStateException("Invalid static export type");
        var fields = (Map<String,Object>) value;
        switch (Objects.toString(fields.get("kind"), "")) {
            case "tycon" -> {
                record(fields, "kind name arguments"); identity(fields.get("name"));
                if (!(fields.get("arguments") instanceof List<?> arguments)) throw new IllegalStateException("Invalid type arguments");
                arguments.forEach(ManagedExportAdmission::type);
            }
            case "application" -> { record(fields, "kind function argument"); type(fields.get("function")); type(fields.get("argument")); }
            case "function" -> { record(fields, "kind multiplicity argument result"); type(fields.get("multiplicity")); type(fields.get("argument")); type(fields.get("result")); }
            default -> throw new IllegalStateException("Unsupported static export type structure");
        }
        return fields;
    }
    private static boolean nominal(Map<String,Object> type, String module, String occurrence, String namespace, int arguments) {
        return Objects.equals(type.get("kind"), "tycon") && Objects.equals(type.get("name"), Map.of("unit", "ghc-internal", "module", module, "occurrence", occurrence, "namespace", namespace)) && type.get("arguments") instanceof List<?> values && values.size() == arguments;
    }
}
