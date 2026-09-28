// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.util.*;

/** Retained wrappers need separately validated managed call adapters. This token
 * grants no native symbol or callback capability. */
public final class ManagedImportAdmission {
    private record Target(Object unit, Object symbol) {}
    private final Map<?,?> module;
    private final Map<Target,Map<String,Object>> generated;
    private ManagedImportAdmission(Map<?,?> module, Map<Target,Map<String,Object>> generated) { this.module = module; this.generated = generated; }
    public Map<?,?> getModule() { return module; }
    public void validateCalls(List<?> actual) {
        for (Object call : actual) {
            if (!(call instanceof Map<?,?> fields) || !(fields.get("target") instanceof Map<?,?> target)) continue;
            var expected = generated.get(new Target(target.get("unit"), target.get("symbol")));
            if (expected != null && !expected.equals(call)) throw new IllegalArgumentException("Generated CAPI call ABI differs");
        }
    }
    private static void requireProof(boolean value, String detail) { if (!value) throw new IllegalArgumentException("Invalid managed static-import provenance: " + detail); }
    private static Map<?,?> record(Object value, String keys) {
        requireProof(value instanceof Map<?,?> fields && fields.keySet().equals(Set.of(keys.split(" "))), "record fields");
        return (Map<?,?>) value;
    }
    private static String text(Object value) {
        requireProof(value instanceof String text && !text.isEmpty() && text.indexOf(0) < 0, "missing text");
        return (String) value;
    }
    private static void nullableText(Object value) { if (value != null) text(value); }
    private static boolean in(Object value, String... choices) { return Arrays.asList(choices).contains(value); }
    private static Map<?,?> identity(Object value) {
        var fields = record(value, "unit module occurrence namespace");
        fields.values().forEach(ManagedImportAdmission::text);
        requireProof(in(fields.get("namespace"), "value", "type", "data"), "name namespace");
        return fields;
    }
    private static final Set<String> PRIMITIVES = Set.of("void", "IntRep", "WordRep", "Int8Rep", "Word8Rep", "Int16Rep", "Word16Rep",
            "Int32Rep", "Word32Rep", "Int64Rep", "Word64Rep", "AddrRep", "FloatRep", "DoubleRep");
    private static List<?> scalars(Object value) {
        boolean valid = value instanceof List<?> fields && !fields.isEmpty();
        if (valid) for (Object item : (List<?>) value) if (!(item instanceof String text) || !PRIMITIVES.contains(text)) { valid = false; break; }
        requireProof(valid, "foreign scalar carriers");
        return (List<?>) value;
    }
    private static List<Object> calls(Object value) {
        var result = new ArrayList<Object>();
        if (value instanceof Map<?,?> fields) {
            if (fields.get("foreignCall") != null) result.add(fields.get("foreignCall"));
            for (Object child : fields.values()) result.addAll(calls(child));
        } else if (value instanceof List<?> fields) for (Object child : fields) result.addAll(calls(child));
        return result;
    }
    private static Map<String,Object> scalar(Object primitive, boolean evaluated) {
        String kind = switch ((String) primitive) { case "void" -> "void"; case "AddrRep" -> "address"; case "FloatRep" -> "float"; case "DoubleRep" -> "double"; default -> "long"; };
        return Map.of("kind", kind, "primReps", Objects.equals(primitive, "void") ? List.of() : List.of(primitive), "evaluated", evaluated);
    }
    private static Map<String,Object> descriptor(Map<?,?> emitted) {
        var arguments = (List<?>) emitted.get("arguments"); var result = (List<?>) emitted.get("result");
        var target = new LinkedHashMap<String,Object>();
        target.put("kind", "static"); target.put("symbol", emitted.get("symbol")); target.put("unit", emitted.get("unit")); target.put("isFunction", true);
        Object convention = emitted.get("convention"), safety = emitted.get("safety");
        long arity = arguments.size(), suppliedArity = arguments.size();
        var argumentReps = new ArrayList<Map<String,Object>>();
        for (Object primitive : arguments) argumentReps.add(scalar(primitive, false));
        var primReps = new ArrayList<Object>();
        for (Object primitive : result) if (!Objects.equals(primitive, "void")) primReps.add(primitive);
        var components = new ArrayList<Map<String,Object>>();
        for (Object primitive : result) components.add(scalar(primitive, true));
        return Map.of("schema", 1L, "target", target, "convention", convention, "safety", safety,
                "arity", arity, "suppliedArity", suppliedArity,
                "argumentReps", Collections.unmodifiableList(argumentReps),
                "resultRep", Map.of("kind", "unknown", "primReps", Collections.unmodifiableList(primReps),
                        "aggregate", "unboxed-tuple", "components", Collections.unmodifiableList(components), "evaluated", false));
    }
    private static boolean version(Object value, int expected) { return Objects.equals(value, expected) || Objects.equals(value, (long) expected); }
    public static ManagedImportAdmission read(Map<?,?> module) { return read(module, true); }
    public static ManagedImportAdmission read(Map<?,?> module, boolean completeBindings) {
        if (!module.containsKey("staticForeignImportStubs")) return null;
        Object raw = module.get("staticForeignImportStubs");
        requireProof(version(module.get("schema"), 2), "archive schema");
        requireProof(!module.containsKey("foreignLink"), "ambiguous native/managed link");
        requireProof(raw instanceof Map<?,?>, "proof record");
        Object status = ((Map<?,?>) raw).get("status");
        boolean addresses = PackageFinalizers.version(((Map<?,?>) raw).get("schema"), 2);
        var proof = record(raw, "schema scope execution profile unit module status" +
                (Objects.equals(status, "verified") ? " wordBits expectedForeign imports expectedCalls" + (addresses ? " addresses" : "") : " reason"));
        requireProof((version(proof.get("schema"), 1) || addresses) && Objects.equals(proof.get("scope"), "retained-static-import-products") &&
                Objects.equals(proof.get("execution"), "not-linked") && Objects.equals(proof.get("profile"), "ghc-9.14.1-thc-only-static-c-imports-v1") &&
                Objects.equals(proof.get("unit"), module.get("unit")) && Objects.equals(proof.get("module"), module.get("module")) && Objects.equals(module.get("ghc"), "9.14.1"),
                "schema/profile/owner");
        text(proof.get("unit")); text(proof.get("module"));
        if (!Objects.equals(status, "verified")) {
            requireProof(in(status, "unclassified", "rejected"), "status"); text(proof.get("reason")); return null;
        }
        requireProof(version(proof.get("wordBits"), 64), "word width");
        PackageFinalizers.declarations(module, proof); // Validate the selected inventory; this grants no callback execution.
        requireProof(Objects.equals(proof.get("expectedForeign"), module.get("foreign")), "retained foreign product differs");
        var foreign = record(module.get("foreign"), "schema execution stubs files");
        requireProof(version(foreign.get("schema"), 1) && Objects.equals(foreign.get("execution"), "not-linked"), "foreign schema/execution");
        var stubs = record(foreign.get("stubs"), "header source initializers finalizers");
        requireProof(Objects.equals(stubs.get("header"), "") && Objects.equals(stubs.get("initializers"), List.of()) &&
                Objects.equals(stubs.get("finalizers"), List.of()) && Objects.equals(foreign.get("files"), List.of()), "unclassified native obligations");
        text(stubs.get("source"));
        if (!(proof.get("imports") instanceof List<?> imports)) throw new IllegalArgumentException("Missing typed static imports");
        requireProof(!imports.isEmpty(), "empty import inventory");
        var binders = new HashSet<Map<?,?>>();
        var generated = new LinkedHashMap<Target,Map<String,Object>>();
        for (Object entry : imports) {
            var item = record(entry, "binder header symbol unit isFunction convention safety declaredType normalizedType normalizationRole emitted");
            var binder = identity(item.get("binder"));
            requireProof(Objects.equals(binder.get("unit"), module.get("unit")) && Objects.equals(binder.get("module"), module.get("module")) &&
                    Objects.equals(binder.get("namespace"), "value") && binders.add(binder), "duplicate or foreign import binder");
            nullableText(item.get("header")); nullableText(item.get("unit")); text(item.get("symbol"));
            requireProof(in(item.get("convention"), "ccall", "capi") && in(item.get("safety"), "safe", "unsafe", "interruptible") &&
                    item.get("isFunction") instanceof Boolean && (Objects.equals(item.get("isFunction"), true) || Objects.equals(item.get("convention"), "capi")) &&
                    Objects.equals(item.get("normalizationRole"), "representational"), "static import declaration");
            PackageScalarLinks.archiveType(item.get("declaredType")); PackageScalarLinks.archiveType(item.get("normalizedType"));
            var emitted = record(item.get("emitted"), "symbol unit convention safety arguments result");
            text(emitted.get("symbol")); nullableText(emitted.get("unit"));
            requireProof(Objects.equals(emitted.get("convention"), item.get("convention")) && Objects.equals(emitted.get("safety"), item.get("safety")) &&
                    Objects.equals(emitted.get("unit"), item.get("unit")), "emitted call ownership/convention");
            var arguments = scalars(emitted.get("arguments")); var result = scalars(emitted.get("result"));
            boolean validState = Objects.equals(arguments.getLast(), "void");
            if (validState) for (Object primitive : arguments.subList(0, arguments.size() - 1)) if ("void".equals(primitive)) { validState = false; break; }
            validState = validState && Objects.equals(result.getFirst(), "void") && result.size() <= 2;
            if (validState) for (Object primitive : result.subList(1, result.size())) if ("void".equals(primitive)) { validState = false; break; }
            requireProof(validState, "State/result shape");
            if (Objects.equals(item.get("convention"), "ccall")) requireProof(Objects.equals(emitted.get("symbol"), item.get("symbol")), "direct C symbol changed");
            else requireProof(generated.put(new Target(emitted.get("unit"), emitted.get("symbol")), descriptor(emitted)) == null, "duplicate generated CAPI target");
        }
        requireProof(!generated.isEmpty(), "no generated CAPI products");
        var actual = calls(module.get("bindings")); CoreCallInventory.check(proof.get("expectedCalls"), actual, completeBindings);
        var admission = new ManagedImportAdmission(module, generated); admission.validateCalls(actual); return admission;
    }
}
