// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.util.*;
import java.util.function.BiPredicate;
import java.util.function.Supplier;

/** Pinned GHC annotation envelopes and the existing THC annotation contracts.
 * Unknown payloads stay opaque; a TypeRep is not an arbitrary Data schema.
 * Private proofs are interpreted only for the caller's declared producer unit.
 * Interpretation supplies archival metadata, never native execution permission. */
final class CoreHiAnnotations {
    record Text(List<Integer> points) {
        Text { points = List.copyOf(points); }
        static Text of(String value) { return new Text(value.codePoints().boxed().toList()); }
        String string() { var out = new StringBuilder(); points.forEach(out::appendCodePoint); return out.toString(); }
    }
    record TyCon(Text unit, Text module, Text name, long kindArguments, Kind kind) {}
    record TypeRep(int tag, List<Object> fields) { TypeRep { fields = List.copyOf(fields); } }
    record Kind(int tag, List<Object> fields) { Kind { fields = List.copyOf(fields); } }
    record RuntimeRep(int tag, List<Object> fields) { RuntimeRep { fields = List.copyOf(fields); } }
    record Target(CoreHiReader.ModuleId module, int namespace, String parent, String occurrence) {
        boolean isModule() { return occurrence == null; }
    }
    record Envelope(Target target, TypeRep type, List<Integer> payload) {
        Envelope { payload = List.copyOf(payload); }
    }
    private final CoreHiReader.ModuleId owner;
    private final List<Envelope> envelopes;
    CoreHiAnnotations(CoreHiReader.ModuleId owner, List<Envelope> envelopes) {
        this.owner = Objects.requireNonNull(owner); this.envelopes = List.copyOf(envelopes);
    }
    List<Envelope> envelopes() { return envelopes; }

    static CoreHiAnnotations read(CoreHiReader reader) {
        var c = reader.cursor(reader.publicSections.get("annotations"), "interface annotations");
        int count = c.count(2); var values = new ArrayList<Envelope>(count);
        for (int i = 0; i < count; i++) {
            int tag = c.byteValue(); c.require(tag <= 1, "invalid annotation target");
            Target target;
            if (tag == 0) {
                int namespace = c.byteValue(); c.require(namespace <= 4, "invalid annotation namespace");
                String parent = namespace == 4 ? reader.fastString(c) : null;
                target = new Target(reader.module, namespace, parent, reader.fastString(c));
            } else {
                target = new Target(reader.module(c), -1, null, null);
            }
            TypeRep type = typeRep(c, 0);
            int length = c.count(1); var payload = new ArrayList<Integer>(length);
            for (int j = 0; j < length; j++) payload.add(c.byteValue());
            values.add(new Envelope(target, type, payload));
        }
        c.expectEnd(); return new CoreHiAnnotations(reader.module, values);
    }
    private static Text text(CoreHiReader.Cursor c) {
        int length = c.count(1); var points = new ArrayList<Integer>(length);
        for (int i = 0; i < length; i++) { long point = c.unsigned(32); c.require(point <= 0x10ffff, "invalid annotation Char"); points.add((int)point); }
        return new Text(points);
    }
    private static TypeRep typeRep(CoreHiReader.Cursor c, int depth) {
        c.require(depth < 256, "annotation TypeRep nesting exceeds native limit");
        int tag = c.byteValue();
        return switch (tag) {
            case 0 -> new TypeRep(0, List.of());
            case 1 -> {
                TyCon con = tyCon(c, depth + 1); int count = c.count(1); var kinds = new ArrayList<TypeRep>(count);
                for (int i = 0; i < count; i++) kinds.add(typeRep(c, depth + 1));
                yield new TypeRep(1, List.of(con, List.copyOf(kinds)));
            }
            case 2 -> new TypeRep(2, List.of(typeRep(c, depth + 1), typeRep(c, depth + 1)));
            default -> throw fail("invalid annotation TypeRep tag " + tag);
        };
    }
    private static TyCon tyCon(CoreHiReader.Cursor c, int depth) {
        Text unit = text(c), module = text(c), name = text(c); long arguments = c.signed();
        c.require(arguments >= 0, "negative annotation kind argument count");
        return new TyCon(unit, module, name, arguments, kind(c, depth + 1));
    }
    private static Kind kind(CoreHiReader.Cursor c, int depth) {
        c.require(depth < 256, "annotation KindRep nesting exceeds native limit"); int tag = c.byteValue();
        return switch (tag) {
            case 0 -> {
                TyCon con = tyCon(c, depth + 1); int count = c.count(1); var args = new ArrayList<Kind>(count);
                for (int i = 0; i < count; i++) args.add(kind(c, depth + 1));
                yield new Kind(0, List.of(con, List.copyOf(args)));
            }
            case 1 -> { long index = c.signed(); c.require(index >= 0, "negative annotation kind binder"); yield new Kind(1, List.of(index)); }
            case 2, 3 -> new Kind(tag, List.of(kind(c, depth + 1), kind(c, depth + 1)));
            case 4 -> new Kind(4, List.of(runtimeRep(c, depth + 1)));
            case 5 -> { int sort = c.byteValue(); c.require(sort <= 2, "invalid annotation type literal sort"); yield new Kind(5, List.of(sort, text(c))); }
            default -> throw fail("invalid annotation KindRep tag " + tag);
        };
    }
    private static RuntimeRep runtimeRep(CoreHiReader.Cursor c, int depth) {
        c.require(depth < 256, "annotation RuntimeRep nesting exceeds native limit"); int tag = c.byteValue();
        if (tag == 0) { int count = c.byteValue(), element = c.byteValue(); c.require(count <= 5 && element <= 9, "invalid annotation vector rep"); return new RuntimeRep(0, List.of(count, element)); }
        if (tag == 1 || tag == 2) { int count = c.count(1); var reps = new ArrayList<RuntimeRep>(count); for (int i = 0; i < count; i++) reps.add(runtimeRep(c, depth + 1)); return new RuntimeRep(tag, List.of(List.copyOf(reps))); }
        c.require(tag <= 17, "invalid annotation RuntimeRep tag"); return new RuntimeRep(tag, List.of());
    }
    private static final Kind LIFTED = new Kind(4, List.of(new RuntimeRep(3, List.of())));
    private static TypeRep nominal(String unit, String module, String name, Kind kind) {
        return new TypeRep(1, List.of(new TyCon(Text.of(unit), Text.of(module), Text.of(name), 0, kind), List.of()));
    }
    private static final TypeRep STRING = new TypeRep(2, List.of(
        nominal("ghc-internal", "GHC.Internal.Types", "List", new Kind(3, List.of(LIFTED, LIFTED))),
        nominal("ghc-internal", "GHC.Internal.Types", "Char", LIFTED)));
    private List<Envelope> owned(String producer, String module, String name) {
        if (producer == null) return List.of();
        TypeRep expected = nominal(producer, module, name, LIFTED);
        return envelopes.stream().filter(e -> e.target.isModule() && e.target.module.equals(owner) && e.type.equals(expected)).toList();
    }
    private Data payload(Envelope e) { return new Data(e.payload); }

    /** Declaration inventory summary needs no dependencies, types or execution. */
    boolean packageScalarDeclarations(String producerUnit) {
        var exports = owned(producerUnit, "THC.ForeignExports", "StaticExports");
        require(exports.size() <= 1, "duplicate static foreign-export annotations");
        boolean found = false;
        if (!exports.isEmpty()) {
            var d = payload(exports.getFirst()); d.constructor(1); long version = d.integer(); String unit = d.string(), module = d.string();
            var entries = d.list(d::staticExport); d.end();
            require(version == 1 && unit.equals(owner.unit()) && module.equals(owner.name()), "static foreign-export annotation version/owner mismatch");
            found = !entries.isEmpty();
        }
        var imports = owned(producerUnit, "THC.ForeignImportProvenance", "ImportProof");
        require(imports.size() <= 1, "duplicate static-import proofs");
        if (!imports.isEmpty()) {
            var d = payload(imports.getFirst()); d.constructor(1); long version = d.integer(); String unit = d.string(), module = d.string(); int evidence = d.constructor(5);
            require(version == (evidence == 5 ? 4 : evidence == 4 ? 3 : evidence == 3 ? 2 : 1) && unit.equals(owner.unit()) && module.equals(owner.name()), "static-import proof version/owner mismatch");
            if (evidence == 1) d.string();
            else {
                found |= !d.list(d::imported).isEmpty();
                if (evidence >= 3) found |= !d.list(d::address).isEmpty();
                if (evidence >= 4) found |= !d.list(d::wrapper).isEmpty();
                d.product(); if (evidence == 5) d.product();
            }
            d.end();
        }
        return found;
    }

    /** actualForeign/calls are the complete retained product and call inventory,
     * not a demanded subset. Null means unavailable, never proven empty.
     * The predicate checks one actual retained export binder's declared type.
     * Call inventory is demanded only by an otherwise verified import proof. */
    Map<String,Object> fields(Map<String,Object> actualForeign, Supplier<? extends List<?>> actualCalls, String producerUnit,
            BiPredicate<Map<String,Object>,Map<String,Object>> binderTypeMatches) {
        var fields = new LinkedHashMap<String,Object>();
        var policy = new TreeMap<String,String>(); String modulePolicy = null;
        for (var envelope : envelopes) if (envelope.type.equals(STRING)) {
            var d = payload(envelope); String raw = d.string(); d.end();
            if (!raw.startsWith("thc:")) continue;
            String backend = switch (raw) {
                case "thc:backend=ast" -> "ast";
                case "thc:backend=bytecode" -> "bytecode";
                default -> { if (raw.startsWith("thc:vectorize")) throw fail("THC ANN vectorize is not supported: vectorization is JVM-global; use the global JVM compiler flag"); throw fail("Unsupported THC ANN option: " + raw); }
            };
            Target target = envelope.target;
            require(target.module.equals(owner) && (target.isModule() || target.namespace == 0), "THC ANN backend requires this module or a top-level function/value binder");
            if (target.isModule()) { require(modulePolicy == null || modulePolicy.equals(backend), "Conflicting THC ANN backend settings for one target"); modulePolicy = backend; }
            else {
                String id = CoreHiNames.id(new CoreHiReader.ExternalName(owner, 0, null, target.occurrence));
                String previous = policy.putIfAbsent(id, backend); require(previous == null || previous.equals(backend), "Conflicting THC ANN backend settings for one target");
            }
        }
        if (modulePolicy != null || !policy.isEmpty()) { var p = new LinkedHashMap<String,Object>(); if (modulePolicy != null) p.put("default", modulePolicy); p.put("bindings", policy); fields.put("backendPolicy", p); }
        var exports = owned(producerUnit, "THC.ForeignExports", "StaticExports");
        require(exports.size() <= 1, "duplicate static foreign-export annotations");
        List<Map<String,Object>> exportEntries = List.of();
        if (!exports.isEmpty()) {
            var d = payload(exports.getFirst()); d.constructor(1); long version = d.integer(); String unit = d.string(), module = d.string();
            exportEntries = d.list(d::staticExport); d.end();
            require(version == 1 && unit.equals(owner.unit()) && module.equals(owner.name()), "static foreign-export annotation version/owner mismatch");
            var symbols = new HashSet<Object>();
            for (var entry : exportEntries) {
                require(symbols.add(entry.get("symbol")), "duplicate static foreign-export symbol");
                require(binderTypeMatches != null && binderTypeMatches.test(record(entry.get("binder")), record(entry.get("declaredType"))), "static foreign-export annotation does not resolve to one actual Core binder with its declared type");
            }
            if (!exportEntries.isEmpty()) fields.put("staticForeignExports", map("schema", version, "producer", "THC.Plugin/typeCheckResultAction", "scope", "static-export-associations", "execution", "not-linked", "unit", unit, "module", module, "exports", exportEntries));
        }
        if (!exportEntries.isEmpty()) registration(fields, actualForeign, producerUnit, exportEntries);
        imports(fields, actualForeign, actualCalls, producerUnit);
        return fields;
    }
    private void registration(Map<String,Object> fields, Map<String,Object> actual, String producer, List<Map<String,Object>> exports) {
        var proofs = owned(producer, "THC.ForeignExportProvenance", "RegistrationProof"); require(proofs.size() <= 1, "duplicate foreign-export registration proofs");
        long version = 1; String status = "unclassified", reason = "missing-registration-provenance"; List<Map<String,Object>> roots = exports.stream().map(e -> record(e.get("binder"))).toList();
        if (!proofs.isEmpty()) {
            var d = payload(proofs.getFirst()); d.constructor(1); version = d.integer(); String unit = d.string(), module = d.string();
            require(version >= 1 && version <= 3 && unit.equals(owner.unit()) && module.equals(owner.name()), "foreign-export registration proof version/owner mismatch");
            int evidence = d.constructor(2);
            if (evidence == 1) reason = d.string();
            else {
                var ordered = d.list(d::identity); var expected = d.product();
                status = "rejected";
                if (!roots.equals(ordered)) reason = "typed-export-roots-differ";
                else if (actual == null) reason = "missing-retained-foreign-product";
                else if (!list(actual.get("files")).isEmpty()) reason = "additional-foreign-files";
                else if (!actual.equals(expected) && !(empty(actual) && empty(expected))) reason = "retained-foreign-product-differs";
                else { status = "verified"; reason = null; }
            }
            d.end();
        }
        String profile = status.equals("verified") && version == 3 ? "ghc-9.14.1-thc-only-native-static-c-products-v3" :
            status.equals("verified") && version == 2 ? "ghc-9.14.1-thc-only-native-static-ccall-imports-v2" : "ghc-9.14.1-thc-only-native-static-ccall-v1";
        var proof = map("schema", 2L, "scope", "retained-foreign-products", "execution", "not-linked", "profile", profile, "status", status);
        if (reason != null) proof.put("reason", reason);
        else { proof.put("roots", roots); proof.put("wordBits", 64L); proof.put("expectedForeign", actual); proof.put("expectedExports", fields.get("staticForeignExports")); }
        fields.put("staticForeignExportRegistration", proof);
    }
    private void imports(Map<String,Object> fields, Map<String,Object> actual, Supplier<? extends List<?>> inventory, String producer) {
        var proofs = owned(producer, "THC.ForeignImportProvenance", "ImportProof"); require(proofs.size() <= 1, "duplicate static-import proofs"); if (proofs.isEmpty()) return;
        var d = payload(proofs.getFirst()); d.constructor(1); long version = d.integer(); String unit = d.string(), module = d.string(); int evidence = d.constructor(5);
        require(version == (evidence == 5 ? 4 : evidence == 4 ? 3 : evidence == 3 ? 2 : 1) && unit.equals(owner.unit()) && module.equals(owner.name()), "static-import proof version/owner mismatch");
        List<Map<String,Object>> imports = List.of(), addresses = List.of(), wrappers = List.of(); Map<String,Object> expected = null, imported = null;
        String status = "unclassified", reason = null; List<?> calls = null;
        if (evidence == 1) { reason = d.string(); d.end(); }
        else {
            imports = d.list(d::imported); if (evidence >= 3) addresses = d.list(d::address); if (evidence >= 4) wrappers = d.list(d::wrapper);
            expected = d.product(); if (evidence == 5) imported = d.product();
            d.end();
            status = "rejected";
            if (actual == null) reason = "missing-retained-foreign-product";
            else if (evidence == 5 && !actual.equals(expected)) reason = "retained-foreign-product-differs";
            else if (!list(actual.get("files")).isEmpty()) reason = "additional-foreign-files";
            else if (!actual.equals(expected)) reason = "retained-foreign-product-differs";
            else {
                var product = evidence == 5 ? imported : expected;
                if (product.get("stubs") instanceof Map<?,?> stubs && !((Objects.equals(stubs.get("header"), "") || !wrappers.isEmpty()) && list(stubs.get("initializers")).isEmpty() && list(stubs.get("finalizers")).isEmpty())) reason = "unexpected-stub-obligations";
                else if (new HashSet<>(imports).size() != imports.size()) reason = "duplicate-static-import-evidence";
                else if (new HashSet<>(addresses).size() != addresses.size()) reason = "duplicate-static-address-evidence";
                else if (new HashSet<>(wrappers).size() != wrappers.size()) reason = "duplicate-wrapper-evidence";
                else {
                    calls = inventory == null ? null : inventory.get();
                    if (calls == null) reason = "missing-retained-Core-call-inventory";
                    else { status = "verified"; reason = null; }
                }
            }
        }
        if (status.equals("verified") && evidence == 5 && imports.isEmpty() && addresses.isEmpty() && wrappers.isEmpty()) return;
        boolean verified = status.equals("verified");
        long schema = verified ? evidence == 5 ? 4 : !wrappers.isEmpty() ? 3 : !addresses.isEmpty() ? 2 : 1 : 1;
        String profile = verified && imports.stream().anyMatch(i -> Objects.equals(i.get("convention"), "prim")) ? "ghc-9.14.1-thc-stock-static-foreign-imports-v2" : "ghc-9.14.1-thc-only-static-c-imports-v1";
        var proof = map("schema", schema, "profile", profile, "scope", "retained-static-import-products", "execution", "not-linked", "unit", owner.unit(), "module", owner.name(), "status", status);
        if (!verified) proof.put("reason", reason);
        else {
            proof.put("wordBits", 64L); proof.put("expectedForeign", actual); proof.put("imports", imports); proof.put("expectedCalls", List.copyOf(calls));
            if (schema >= 2) proof.put("addresses", addresses); if (schema >= 3) proof.put("wrappers", wrappers); if (schema == 4) proof.put("importForeign", imported);
        }
        if (!(verified && evidence != 5 && wrappers.isEmpty() && imports.isEmpty() && addresses.isEmpty())) fields.put("staticForeignImports", proof);
        if (actual == null || !empty(verified && evidence == 5 ? imported : actual)) fields.put("staticForeignImportStubs", proof);
    }
    private static boolean empty(Map<String,Object> product) {
        if (product == null || !list(product.get("files")).isEmpty()) return false;
        Object raw = product.get("stubs");
        return raw == null || raw instanceof Map<?,?> stub && Objects.equals(stub.get("header"), "") && Objects.equals(stub.get("source"), "") && list(stub.get("initializers")).isEmpty() && list(stub.get("finalizers")).isEmpty();
    }

    /** The Data codec is interpreted with a consumer's actual algebraic schema.
     * In particular constructor tags never infer an unknown datatype's arity. */
    private static final class Data {
        private final List<Integer> bytes; private int position;
        Data(List<Integer> bytes) { this.bytes = bytes; }
        int byteValue() { require(position < bytes.size(), "truncated annotation Data payload"); int b = bytes.get(position++); require(b >= 0 && b <= 255, "invalid annotation payload byte"); return b; }
        long fixedInt() { long n = 0; for (int i = 0; i < 8; i++) n |= (long)byteValue() << (i * 8); return n; }
        int constructor(int max) { require(byteValue() == 1, "annotation Data requires algebraic constructor"); long index = fixedInt(); require(index >= 1 && index <= max, "invalid annotation Data constructor " + index); return (int)index; }
        void end() { require(position == bytes.size(), "trailing annotation Data payload"); }
        long integer() { require(byteValue() == 2, "annotation Data requires Int"); String number = rawString(); try { return Long.parseLong(number); } catch (NumberFormatException failure) { throw fail("invalid annotation Int"); } }
        String rawString() {
            long length = fixedInt(); require(length >= 0 && length <= (bytes.size() - position) / 8, "invalid annotation serialized String length");
            var text = new StringBuilder(); for (long i = 0; i < length; i++) { long cp = fixedInt(); require(cp >= 0 && cp <= 0x10ffff, "invalid annotation serialized Char"); text.appendCodePoint((int)cp); } return text.toString();
        }
        int character() {
            require(byteValue() == 4, "annotation Data requires Char"); String shown = rawString();
            require(shown.length() >= 3 && shown.charAt(0) == '\'' && shown.charAt(shown.length() - 1) == '\'', "invalid serialized Char literal");
            String body = shown.substring(1, shown.length() - 1); int value;
            if (!body.startsWith("\\")) { require(body.codePointCount(0, body.length()) == 1, "invalid serialized Char literal"); value = body.codePointAt(0); }
            else {
                String escape = body.substring(1);
                String controls = "NUL SOH STX ETX EOT ENQ ACK BEL BS HT LF VT FF CR SO SI DLE DC1 DC2 DC3 DC4 NAK SYN ETB CAN EM SUB ESC FS GS RS US";
                value = switch (escape) { case "a" -> 7; case "b" -> 8; case "f" -> 12; case "n" -> 10; case "r" -> 13; case "t" -> 9; case "v" -> 11; case "\\" -> 92; case "'" -> 39; case "DEL" -> 127; default -> {
                    var names = List.of(controls.split(" ")); int index = names.indexOf(escape);
                    if (index >= 0) yield index;
                    try { yield Integer.parseInt(escape); } catch (NumberFormatException failure) { throw fail("invalid serialized Char escape"); }
                }};
            }
            require(value >= 0 && value <= 0x10ffff, "invalid serialized Char range"); return value;
        }
        String string() { var out = new StringBuilder(); while (constructor(2) == 2) out.appendCodePoint(character()); return out.toString(); }
        boolean bool() { return constructor(2) == 2; }
        <T> List<T> list(Supplier<T> element) { var result = new ArrayList<T>(); while (constructor(2) == 2) result.add(element.get()); return List.copyOf(result); }
        <T> T optional(Supplier<T> value) { return constructor(2) == 1 ? null : value.get(); }
        Map<String,Object> identity() { constructor(1); return map("unit", string(), "module", string(), "occurrence", string(), "namespace", string()); }
        Map<String,Object> type(boolean polymorphic, int depth) {
            require(depth < 256, "annotation nominal type nesting exceeds native limit");
            return switch (constructor(polymorphic ? 5 : 3)) {
                case 1 -> map("kind", "tycon", "name", identity(), "arguments", list(() -> type(polymorphic, depth + 1)));
                case 2 -> map("kind", "application", "function", type(polymorphic, depth + 1), "argument", type(polymorphic, depth + 1));
                case 3 -> map("kind", "function", "multiplicity", type(polymorphic, depth + 1), "argument", type(polymorphic, depth + 1), "result", type(polymorphic, depth + 1));
                case 4 -> { long index = integer(); require(index >= 0, "negative annotation bound variable index"); yield map("kind", "bound-variable", "index", index); }
                case 5 -> map("kind", "forall", "binderKind", type(true, depth + 1), "body", type(true, depth + 1));
                default -> throw new AssertionError();
            };
        }
        Map<String,Object> staticExport() {
            constructor(1); return map("binder", identity(), "symbol", string(), "convention", string(), "declaredType", type(false, 0), "normalizedType", type(false, 0), "normalizationRole", "representational", "arguments", list(() -> type(false, 0)), "result", type(false, 0), "effect", bool() ? "io" : "pure");
        }
        Map<String,Object> call() { constructor(1); return map("symbol", string(), "unit", optional(this::string), "convention", string(), "safety", string(), "arguments", list(this::string), "result", list(this::string)); }
        Map<String,Object> imported() {
            constructor(1); return map("binder", identity(), "header", optional(this::string), "symbol", string(), "unit", optional(this::string), "isFunction", bool(), "convention", string(), "safety", string(), "declaredType", type(true, 0), "normalizedType", type(true, 0), "normalizationRole", "representational", "emitted", call());
        }
        Map<String,Object> address() {
            constructor(1); return map("binder", identity(), "header", optional(this::string), "symbol", string(), "isFunction", bool(), "convention", string(), "declaredType", type(true, 0), "normalizedType", type(true, 0), "normalizationRole", "representational", "callback", optional(() -> { constructor(1); return map("arguments", list(this::string), "result", string()); }));
        }
        Map<String,Object> wrapper() {
            constructor(1); return map("binder", identity(), "helper", string(), "convention", string(), "declaredType", type(true, 0), "normalizedType", type(true, 0), "normalizationRole", "representational", "arguments", list(() -> type(true, 0)), "result", type(true, 0), "effect", bool() ? "io" : "pure", "typeString", string());
        }
        Map<String,Object> product() {
            constructor(1); Object stub = optional(() -> { constructor(1); return map("header", string(), "source", string(), "initializers", list(this::label), "finalizers", list(this::label)); });
            var files = list(() -> { constructor(1); return map("language", string(), "source", string(), "extension", string()); });
            return map("schema", 1L, "execution", "not-linked", "stubs", stub, "files", files);
        }
        Map<String,Object> label() { constructor(1); return map("isInitializer", bool(), "unit", string(), "module", string(), "name", string()); }
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> record(Object value) { require(value instanceof Map<?,?>, "invalid annotation record"); return (Map<String,Object>)value; }
    private static List<?> list(Object value) { require(value instanceof List<?>, "invalid annotation list"); return (List<?>)value; }
    private static LinkedHashMap<String,Object> map(Object... pairs) { var result = new LinkedHashMap<String,Object>(); for (int i = 0; i < pairs.length; i += 2) result.put((String)pairs[i], pairs[i + 1]); return result; }
    private static IllegalArgumentException fail(String reason) { return new IllegalArgumentException("native interface annotations: " + reason); }
    private static void require(boolean condition, String reason) { if (!condition) throw fail(reason); }
}
