// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.math.BigInteger;
import java.util.*;
import thc.runtime.CoreFloatingLiteral;

/** Selected typed records decoded into the existing lowering input. The wire
 * contains Core records, not a second generic object serialization. */
public final class CoreCompactRecords {
    public record Origin(String container, long dataOffset, long bindingOffset, CoreCompactDebug debug) {
        public Origin(String container, long dataOffset) { this(container, dataOffset, dataOffset, null); }
        public Origin(String container, long dataOffset, long bindingOffset) { this(container, dataOffset, bindingOffset, null); }
        public String getContainer() { return container; } public long getDataOffset() { return dataOffset; }
        public long getBindingOffset() { return bindingOffset; } public CoreCompactDebug getDebug() { return debug; }
    }
    private final CoreCompactFile file;
    private final String identity;
    private final CoreCompactDebug debug;
    private long bindingOffset;
    private static final Object MISSING = new Object();
    private record Shape(Map<String,Object> fields) {}
    private record StringSpan(long offset, long length) {}
    private final Map<Long,Shape> shapes = new HashMap<>();
    private final Set<Long> readingShapes = new HashSet<>();
    private final Map<StringSpan,String> strings = new HashMap<>();
    public CoreCompactRecords(CoreCompactFile file, String identity) { this.file = file; this.identity = identity; debug = new CoreCompactDebug(file); }
    private Origin origin(long offset) { return new Origin(identity, offset, bindingOffset, debug); }
    private String text(CoreCompactCursor cursor) throws Throwable {
        var span = new StringSpan(cursor.unsigned(), cursor.unsigned());
        String existing = strings.get(span);
        if (existing != null) return existing;
        String value = file.string(span.offset, span.length);
        strings.put(span, value);
        return value;
    }
    private String ordinal(CoreCompactCursor cursor) { return "\u0000compact-local:" + cursor.unsigned(); }
    private String id(CoreCompactCursor cursor) throws Throwable {
        int tag = cursor.readByte();
        return switch (tag) { case 0 -> text(cursor); case 1 -> ordinal(cursor); default -> throw error("Invalid compact Core identity tag: " + tag); };
    }
    private void entry(CoreCompactCursor cursor, Map<String,Object> target) {
        int tag = cursor.readByte();
        switch (tag) {
            case 0 -> {} case 1 -> target.put("type", "IO ()"); case 2 -> target.put("type", "State# RealWorld");
            default -> throw error("Invalid compact Core entry type: " + tag);
        }
    }
    @FunctionalInterface private interface Reader<T> { T read() throws Throwable; }
    private static <T> List<T> list(CoreCompactCursor cursor, Reader<T> read) throws Throwable {
        int count = cursor.count();
        var result = new ArrayList<T>(count);
        for (int i = 0; i < count; i++) result.add(read.read());
        return result;
    }
    private static <T> Object presence(CoreCompactCursor cursor, Reader<T> read) throws Throwable {
        int tag = cursor.readByte();
        return switch (tag) { case 0 -> MISSING; case 1 -> null; case 2 -> read.read(); default -> throw error("Invalid compact Core presence tag: " + tag); };
    }
    private static <T> void field(CoreCompactCursor cursor, Map<String,Object> target, String key, Reader<T> read) throws Throwable {
        Object value = presence(cursor, read);
        if (value != MISSING) target.put(key, value);
    }
    private static <T> Object element(CoreCompactCursor cursor, Reader<T> read) throws Throwable {
        Object value = presence(cursor, read);
        require(value != MISSING, "Missing compact Core array element");
        return value;
    }
    private static String enumeration(CoreCompactCursor cursor, List<String> values) {
        int tag = cursor.readByte();
        if (tag >= values.size()) throw error("Invalid compact Core enumeration");
        return values.get(tag);
    }
    private Map<String,Object> vector(CoreCompactCursor cursor) { return map("lanes", cursor.unsigned(), "element", enumeration(cursor, ELEMENTS)); }
    private String primitive(CoreCompactCursor cursor) {
        int tag = cursor.readByte();
        if (tag == 16) { var vector = vector(cursor); return "VecRep " + vector.get("lanes") + " " + vector.get("element"); }
        if (tag >= PRIMITIVES.size()) throw error("Invalid compact Core primitive representation: " + tag);
        return PRIMITIVES.get(tag);
    }
    private Shape shapeUse(CoreCompactCursor cursor) throws Throwable {
        long at = cursor.getPosition();
        int tag = cursor.readByte();
        return switch (tag) {
            case 0 -> {
                require(readingShapes.add(at), "Cyclic compact Core shape reference");
                try {
                    var decoded = shape(cursor, false);
                    var previous = shapes.putIfAbsent(at, decoded);
                    require(previous == null || previous.equals(decoded), "Inconsistent compact Core shape definition");
                    yield decoded;
                } finally { readingShapes.remove(at); }
            }
            case 1 -> {
                long target = cursor.unsigned();
                require(target < at && !readingShapes.contains(target), "Forward or cyclic compact Core shape reference");
                var previous = shapes.get(target);
                if (previous != null) yield previous;
                yield file.data(target, selected -> {
                    require(selected.readByte() == 0, "Compact Core shape reference is not a definition");
                    require(readingShapes.add(target), "Cyclic compact Core shape reference");
                    try { var decoded = shape(selected, false); shapes.put(target, decoded); return decoded; }
                    finally { readingShapes.remove(target); }
                });
            }
            default -> throw error("Invalid compact Core shape tag: " + tag);
        };
    }
    private Shape shape(CoreCompactCursor cursor, boolean inline) throws Throwable {
        var fields = map("kind", enumeration(cursor, KINDS));
        field(cursor, fields, "primReps", () -> list(cursor, () -> primitive(cursor)));
        field(cursor, fields, "vector", () -> vector(cursor));
        field(cursor, fields, "aggregate", () -> enumeration(cursor, List.of("unboxed-tuple", "unboxed-sum")));
        for (String key : List.of("components", "alternatives")) field(cursor, fields, key, () -> list(cursor, () -> inline ? shape(cursor, true) : shapeUse(cursor)));
        field(cursor, fields, "tagSlot", cursor::unsigned);
        field(cursor, fields, "alternativeSlots", () -> list(cursor, () -> list(cursor, cursor::unsigned)));
        return new Shape(Collections.unmodifiableMap(fields));
    }
    private Map<String,Object> evaluation(CoreCompactCursor cursor, Shape shape) throws Throwable {
        var result = new LinkedHashMap<>(shape.fields);
        field(cursor, result, "evaluated", cursor::readBoolean);
        for (String key : List.of("components", "alternatives")) {
            if (!(shape.fields.get(key) instanceof List<?> children)) continue;
            var values = new ArrayList<Map<String,Object>>(children.size());
            for (Object child : children) values.add(evaluation(cursor, (Shape) child));
            result.put(key, values);
        }
        return result;
    }
    private Map<String,Object> rep(CoreCompactCursor cursor) throws Throwable { return rep(cursor, false); }
    private Map<String,Object> rep(CoreCompactCursor cursor, boolean inline) throws Throwable {
        return evaluation(cursor, inline ? shape(cursor, true) : shapeUse(cursor));
    }
    public Map<String,Object> representation(long offset) {
        try { return file.data(offset, this::rep); } catch (Throwable failure) { return rethrow(failure); }
    }
    private Map<String,Object> info(CoreCompactCursor cursor) throws Throwable {
        var result = map();
        field(cursor, result, "joinArity", cursor::unsigned);
        field(cursor, result, "cbvEligible", cursor::readBoolean);
        field(cursor, result, "cbvMarks", () -> list(cursor, cursor::readBoolean));
        return result;
    }
    private Map<String,Object> binder(CoreCompactCursor cursor) throws Throwable {
        var origin = origin(cursor.getPosition());
        String id = ordinal(cursor);
        var result = map("id", id, "name", id, "compactOrigin", origin);
        entry(cursor, result);
        field(cursor, result, "lifted", cursor::readBoolean);
        field(cursor, result, "coercion", cursor::readBoolean);
        field(cursor, result, "rep", () -> rep(cursor));
        field(cursor, result, "info", () -> info(cursor));
        return result;
    }
    public Map<String,Object> binding(long offset) {
        try {
            return file.data(offset, cursor -> {
                long previous = bindingOffset;
                bindingOffset = offset;
                try {
                    var binding = binding(cursor);
                    require(!((String) binding.get("id")).startsWith("\u0000compact-local:"), "Compact top-level binding has local identity");
                    return binding;
                } finally { bindingOffset = previous; }
            });
        } catch (Throwable failure) { return rethrow(failure); }
    }
    private Map<String,Object> binding(CoreCompactCursor cursor) throws Throwable {
        var origin = origin(cursor.getPosition());
        String id = id(cursor);
        var result = map("id", id, "name", id, "compactOrigin", origin);
        entry(cursor, result);
        field(cursor, result, "lifted", cursor::readBoolean);
        result.put("arity", cursor.unsigned());
        field(cursor, result, "rep", () -> rep(cursor));
        field(cursor, result, "info", () -> info(cursor));
        field(cursor, result, "entryStrict", () -> list(cursor, cursor::readBoolean));
        field(cursor, result, "entryStrictSource", () -> text(cursor));
        field(cursor, result, "joinValueArity", cursor::unsigned);
        field(cursor, result, "joinResultRep", () -> rep(cursor));
        result.put("expr", expression(cursor));
        return result;
    }
    private Map<String,Object> demand(CoreCompactCursor cursor) throws Throwable { return map("arity", cursor.unsigned(), "strictArgs", list(cursor, cursor::readBoolean)); }
    private Map<String,Object> family(CoreCompactCursor cursor) throws Throwable { return map("typeConstructor", text(cursor), "constructors", texts(cursor)); }
    private Map<String,Object> tagFamily(CoreCompactCursor cursor) throws Throwable {
        var result = family(cursor);
        result.put("smallFamilyLimit", cursor.unsigned()); result.put("smallFamily", cursor.readBoolean());
        return result;
    }
    private Map<String,Object> foreign(CoreCompactCursor cursor, boolean inline) throws Throwable {
        var result = map("schema", cursor.unsigned());
        int tag = cursor.readByte();
        switch (tag) {
            case 0 -> {
                var target = map("kind", "static", "symbol", text(cursor));
                field(cursor, target, "unit", () -> text(cursor));
                target.put("isFunction", cursor.readBoolean());
                result.put("target", target);
            }
            case 1 -> result.put("target", map("kind", "dynamic"));
            default -> throw error("Invalid compact Core foreign target: " + tag);
        }
        result.put("convention", convention(cursor)); result.put("safety", safety(cursor));
        result.put("arity", cursor.unsigned()); result.put("suppliedArity", cursor.unsigned());
        result.put("argumentReps", list(cursor, () -> rep(cursor, inline))); result.put("resultRep", rep(cursor, inline));
        field(cursor, result, "intrinsic", () -> text(cursor)); field(cursor, result, "javascriptSource", () -> text(cursor));
        return result;
    }
    private Map<String,Object> meta(CoreCompactCursor cursor, Origin origin) throws Throwable {
        var result = map("compactOrigin", origin);
        field(cursor, result, "rep", () -> rep(cursor)); field(cursor, result, "resultRep", () -> rep(cursor));
        field(cursor, result, "entryStrict", () -> list(cursor, cursor::readBoolean));
        field(cursor, result, "entryStrictSource", () -> text(cursor)); field(cursor, result, "callDemand", () -> demand(cursor));
        field(cursor, result, "foreignCall", () -> foreign(cursor, false));
        field(cursor, result, "exceptionPayload", () -> map("schema", cursor.unsigned(), "type", text(cursor)));
        field(cursor, result, "enumFamily", () -> family(cursor)); field(cursor, result, "dataToTagFamily", () -> tagFamily(cursor));
        field(cursor, result, "unsafeEqualityCase", () -> text(cursor));
        return result;
    }
    private List<Object> expression(CoreCompactCursor cursor) throws Throwable {
        var origin = origin(cursor.getPosition());
        int tag = cursor.readByte();
        require(tag <= 8, "Invalid compact Core expression tag: " + tag);
        var metadata = meta(cursor, origin);
        List<Object> fields = switch (tag) {
            case 0 -> values("var", id(cursor));
            case 1 -> values("prim", text(cursor));
            case 2 -> { var result = values("lit"); result.addAll(literal(cursor)); yield result; }
            case 3 -> values("lam", list(cursor, () -> binder(cursor)), expression(cursor));
            case 4 -> values("con", text(cursor), cursor.unsigned());
            case 5 -> values("app", expression(cursor), list(cursor, () -> expression(cursor)),
                    list(cursor, () -> element(cursor, cursor::readBoolean)), cursor.readBoolean(), cursor.readBoolean());
            case 6 -> values("let", cursor.readBoolean(), list(cursor, () -> binding(cursor)), expression(cursor));
            case 7 -> {
                var scrutinee = expression(cursor);
                String binderId = ordinal(cursor);
                field(cursor, metadata, "binder", () -> {
                    var binder = binder(cursor);
                    require(Objects.equals(binder.get("id"), binderId), "Compact case binder identity disagrees");
                    return binder;
                });
                yield values("case", scrutinee, binderId, list(cursor, () -> alternative(cursor)));
            }
            case 8 -> values("void");
            default -> throw error("Invalid compact Core expression tag: " + tag);
        };
        fields.add(metadata);
        return fields;
    }
    private List<Object> alternative(CoreCompactCursor cursor) throws Throwable {
        int tag = cursor.readByte();
        String kind;
        Object discriminator;
        switch (tag) {
            case 0 -> { kind = "default"; discriminator = null; }
            case 1 -> { kind = "data"; discriminator = text(cursor); }
            case 2 -> { kind = "lit"; discriminator = literal(cursor); }
            default -> throw error("Invalid compact Core alternative tag: " + tag);
        }
        var binders = list(cursor, () -> binder(cursor));
        return values(kind, discriminator, binders.stream().map(binder -> binder.get("id")).toList(), expression(cursor), map("binders", binders));
    }
    private List<Object> literal(CoreCompactCursor cursor) throws Throwable {
        int tag = cursor.readByte();
        if (tag >= LITERALS.size()) throw error("Invalid compact Core literal tag: " + tag);
        String kind = LITERALS.get(tag);
        Object value = switch (tag) {
            case 0, 2, 3, 4, 5 -> {
                long number = cursor.signed();
                int width = switch (tag) { case 2 -> 8; case 3 -> 16; case 4 -> 32; default -> 64; };
                require(width == 64 || number >= -(1L << (width - 1)) && number < (1L << (width - 1)), "Out-of-range compact signed literal");
                yield Long.toString(number);
            }
            case 1, 6, 7, 8, 9 -> {
                long number = cursor.unsignedBits();
                int width = switch (tag) { case 6 -> 8; case 7 -> 16; case 8 -> 32; default -> 64; };
                require(width == 64 || number >= 0 && number < (1L << width), "Out-of-range compact unsigned literal");
                yield Long.toUnsignedString(number);
            }
            case 10 -> {
                byte[] magnitude = cursor.bytes(cursor.count());
                require(magnitude.length == 0 || magnitude[magnitude.length - 1] != 0, "Noncanonical compact BigNat magnitude");
                for (int i = 0, j = magnitude.length - 1; i < j; i++, j--) { byte b = magnitude[i]; magnitude[i] = magnitude[j]; magnitude[j] = b; }
                yield magnitude.length == 0 ? "0" : new BigInteger(1, magnitude).toString();
            }
            case 11 -> { long point = cursor.unsigned(); require(point <= 0x10ffff, "Invalid compact Char code point"); yield Long.toString(point); }
            case 12 -> blob(cursor);
            case 13 -> new CoreFloatingLiteral.Single((int) cursor.u32());
            case 14 -> new CoreFloatingLiteral.Double(cursor.fixedBits());
            case 15 -> "0"; case 16 -> primitive(cursor); case 17, 18 -> text(cursor);
            default -> throw error("Unreachable compact literal tag");
        };
        return values(kind, value);
    }
    public Map<String,Object> constructor(CoreCompactCursor cursor) {
        try {
            String id = text(cursor);
            var result = map("id", id, "name", id, "arity", cursor.unsigned(), "tag", cursor.unsigned(),
                    "kind", enumeration(cursor, List.of("boxed", "unboxed-tuple", "unboxed-sum", "newtype")),
                    "strictFields", list(cursor, cursor::readBoolean),
                    "fieldLifted", list(cursor, () -> element(cursor, cursor::readBoolean)),
                    "fieldReps", list(cursor, () -> element(cursor, () -> list(cursor, () -> primitive(cursor)))),
                    "fieldTypes", list(cursor, () -> rep(cursor, true)));
            field(cursor, result, "sumArity", cursor::unsigned);
            field(cursor, result, "enumFamily", () -> family(cursor)); field(cursor, result, "dataToTagFamily", () -> tagFamily(cursor));
            return result;
        } catch (Throwable failure) { return rethrow(failure); }
    }
    /** Header shapes are inline: metadata admission never reads an executable body. */
    public Map<String,Object> header() {
        try {
            return file.facts(cursor -> {
                var result = map("schema", cursor.unsigned(), "ghc", text(cursor), "unit", text(cursor), "module", text(cursor), "boundary", text(cursor));
                field(cursor, result, "providedModules", () -> texts(cursor)); field(cursor, result, "targetLayout", () -> targetLayout(cursor));
                result.put("constructors", list(cursor, () -> constructor(cursor)));
                field(cursor, result, "foreign", () -> artifacts(cursor));
                field(cursor, result, "foreignExceptionBridge", () -> {
                    var bridge = map("schema", cursor.unsigned());
                    for (String key : List.of("unit", "module", "box", "project", "payloadType", "exceptionType")) bridge.put(key, text(cursor));
                    return bridge;
                });
                field(cursor, result, "foreignExceptionBridgeUnit", () -> text(cursor));
                field(cursor, result, "foreignLink", () -> foreignLink(cursor));
                field(cursor, result, "staticForeignImportStubs", () -> importProof(cursor));
                field(cursor, result, "staticForeignImports", () -> importProof(cursor));
                field(cursor, result, "staticForeignExports", () -> exports(cursor));
                field(cursor, result, "staticForeignExportRegistration", () -> registration(cursor));
                field(cursor, result, "packageScalarLink", () -> scalarLink(cursor));
                field(cursor, result, "packageNativeLink", () -> nativeLink(cursor));
                field(cursor, result, "packageNativeArchive", () -> nativeArchive(cursor));
                cursor.expectEnd();
                return result;
            });
        } catch (Throwable failure) { return rethrow(failure); }
    }
    private Map<String,Object> targetLayout(CoreCompactCursor cursor) throws Throwable {
        var result = map("format", "thc-target-layout", "schema", cursor.unsigned());
        result.put("compiler", strings(cursor, "id", "abi", "platform", "way"));
        var layout = map("schema", cursor.unsigned(), "profiled", cursor.readBoolean(), "wordBytes", cursor.unsigned(),
                "endianness", enumeration(cursor, List.of("little", "big")), "targetPlatform", text(cursor), "tablesNextToCode", cursor.readBoolean());
        for (String key : LAYOUT_NUMBERS) layout.put(key, cursor.unsigned());
        result.put("layout", layout);
        return result;
    }
    private Map<String,Object> artifacts(CoreCompactCursor cursor) throws Throwable {
        var result = map("schema", cursor.unsigned(), "execution", text(cursor));
        field(cursor, result, "stubs", () -> {
            var stubs = map("header", text(cursor), "source", text(cursor));
            for (String key : List.of("initializers", "finalizers")) stubs.put(key, list(cursor, () ->
                    map("isInitializer", cursor.readBoolean(), "unit", text(cursor), "module", text(cursor), "name", text(cursor))));
            return stubs;
        });
        result.put("files", list(cursor, () -> map("language", text(cursor), "source", text(cursor), "extension", text(cursor))));
        return result;
    }
    private List<String> texts(CoreCompactCursor cursor) throws Throwable { return list(cursor, () -> text(cursor)); }
    private Map<String,Object> strings(CoreCompactCursor cursor, String... keys) throws Throwable {
        var result = map(); for (String key : keys) result.put(key, text(cursor)); return result;
    }
    private String convention(CoreCompactCursor cursor) { return enumeration(cursor, List.of("ccall", "capi", "stdcall", "prim", "javascript")); }
    private String safety(CoreCompactCursor cursor) { return enumeration(cursor, List.of("unsafe", "safe", "interruptible")); }
    private Map<String,Object> qualifiedName(CoreCompactCursor cursor) throws Throwable { return strings(cursor, "unit", "module", "occurrence", "namespace"); }
    private Map<String,Object> foreignType(CoreCompactCursor cursor) throws Throwable {
        int tag = cursor.readByte();
        return switch (tag) {
            case 0 -> map("kind", "tycon", "name", qualifiedName(cursor), "arguments", list(cursor, () -> foreignType(cursor)));
            case 1 -> map("kind", "application", "function", foreignType(cursor), "argument", foreignType(cursor));
            case 2 -> map("kind", "function", "multiplicity", foreignType(cursor), "argument", foreignType(cursor), "result", foreignType(cursor));
            case 3 -> map("kind", "bound-variable", "index", cursor.unsigned());
            case 4 -> map("kind", "forall", "binderKind", foreignType(cursor), "body", foreignType(cursor));
            default -> throw error("Invalid compact foreign type: " + tag);
        };
    }
    private Map<String,Object> emitted(CoreCompactCursor cursor) throws Throwable {
        var result = strings(cursor, "symbol");
        field(cursor, result, "unit", () -> text(cursor));
        result.put("convention", convention(cursor)); result.put("safety", safety(cursor));
        result.put("arguments", texts(cursor)); result.put("result", texts(cursor));
        return result;
    }
    private Map<String,Object> importProof(CoreCompactCursor cursor) throws Throwable {
        var result = map("schema", cursor.unsigned());
        result.putAll(strings(cursor, "scope", "execution", "profile", "unit", "module"));
        String status = enumeration(cursor, List.of("unclassified", "rejected", "verified"));
        result.put("status", status);
        if (!status.equals("verified")) result.put("reason", text(cursor));
        else {
            result.put("wordBits", cursor.unsigned()); result.put("expectedForeign", artifacts(cursor));
            result.put("imports", list(cursor, () -> {
                var entry = map("binder", qualifiedName(cursor));
                field(cursor, entry, "header", () -> text(cursor)); entry.put("symbol", text(cursor));
                field(cursor, entry, "unit", () -> text(cursor)); entry.put("isFunction", cursor.readBoolean());
                entry.put("convention", convention(cursor)); entry.put("safety", safety(cursor));
                entry.put("declaredType", foreignType(cursor)); entry.put("normalizedType", foreignType(cursor));
                entry.put("normalizationRole", text(cursor)); entry.put("emitted", emitted(cursor));
                return entry;
            }));
            result.put("expectedCalls", list(cursor, () -> foreign(cursor, true)));
        }
        return result;
    }
    private Map<String,Object> exports(CoreCompactCursor cursor) throws Throwable {
        var result = map("schema", cursor.unsigned());
        result.putAll(strings(cursor, "producer", "scope", "execution", "unit", "module"));
        result.put("exports", list(cursor, () -> map("binder", qualifiedName(cursor), "symbol", text(cursor),
                "convention", convention(cursor), "declaredType", foreignType(cursor), "normalizedType", foreignType(cursor),
                "normalizationRole", text(cursor), "arguments", list(cursor, () -> foreignType(cursor)),
                "result", foreignType(cursor), "effect", enumeration(cursor, List.of("pure", "io")))));
        return result;
    }
    private Map<String,Object> registration(CoreCompactCursor cursor) throws Throwable {
        var result = map("schema", cursor.unsigned());
        result.putAll(strings(cursor, "scope", "execution", "profile"));
        String status = enumeration(cursor, List.of("unclassified", "rejected", "verified"));
        result.put("status", status);
        if (!status.equals("verified")) result.put("reason", text(cursor));
        else {
            result.put("roots", list(cursor, () -> qualifiedName(cursor))); result.put("wordBits", cursor.unsigned());
            result.put("expectedForeign", artifacts(cursor)); result.put("expectedExports", exports(cursor));
        }
        return result;
    }
    private String blob(CoreCompactCursor cursor) { return HexFormat.of().formatHex(cursor.bytes(cursor.count())); }
    private Map<String,Object> foreignLink(CoreCompactCursor cursor) throws Throwable {
        var result = map("schema", cursor.unsigned());
        result.putAll(strings(cursor, "format", "unit", "module", "sourceSha256", "bitcodeSha256"));
        result.put("bitcodeHex", blob(cursor)); result.put("target", text(cursor)); result.put("symbols", texts(cursor));
        result.put("abi", list(cursor, () -> strings(cursor, "symbol", "kind")));
        field(cursor, result, "headerHashes", () -> list(cursor, () -> strings(cursor, "name", "sha256")));
        return result;
    }
    private Map<String,Object> linkPayload(CoreCompactCursor cursor) throws Throwable {
        var result = map("schema", cursor.unsigned());
        result.putAll(strings(cursor, "format", "profile", "unit", "target", "componentSha256", "bitcodeSha256"));
        result.put("bitcodeHex", blob(cursor));
        return result;
    }
    private Map<String,Object> scalarLink(CoreCompactCursor cursor) throws Throwable {
        var result = linkPayload(cursor);
        result.put("abi", list(cursor, () -> {
            var entry = strings(cursor, "symbol", "entry"); entry.put("arguments", texts(cursor)); entry.put("result", text(cursor)); return entry;
        }));
        return result;
    }
    private Map<String,Object> nativeLink(CoreCompactCursor cursor) throws Throwable {
        var result = linkPayload(cursor);
        result.put("abi", list(cursor, () -> {
            var entry = strings(cursor, "symbol", "entry"); entry.put("convention", convention(cursor)); entry.put("safety", safety(cursor));
            entry.put("arguments", texts(cursor)); entry.put("result", text(cursor)); return entry;
        }));
        field(cursor, result, "buildInputs", () -> nativeBuildInputs(cursor)); field(cursor, result, "availableEntries", () -> texts(cursor));
        return result;
    }
    private Map<String,Object> compileInput(CoreCompactCursor cursor) throws Throwable {
        var result = strings(cursor, "compiler", "clang");
        result.put("arguments", texts(cursor)); field(cursor, result, "language", () -> text(cursor));
        result.putAll(strings(cursor, "nativeTarget", "target"));
        result.put("files", list(cursor, () -> strings(cursor, "path", "sha256")));
        return result;
    }
    private Map<String,Object> nativeBuildInputs(CoreCompactCursor cursor) throws Throwable {
        var result = map();
        result.put("translationUnits", list(cursor, () -> {
            int tag = cursor.readByte();
            return switch (tag) { case 0 -> compileInput(cursor); case 1 -> list(cursor, () -> compileInput(cursor)); default -> throw error("Invalid compact native compile group: " + tag); };
        }));
        result.put("providers", list(cursor, () -> {
            var entry = strings(cursor, "provider"); entry.put("symbols", texts(cursor)); entry.putAll(strings(cursor, "bitcode", "bitcodeSha256", "target"));
            entry.put("inputs", compileInput(cursor)); return entry;
        }));
        field(cursor, result, "dependencies", () -> list(cursor, () -> nativeDependency(cursor)));
        result.put("nativeLibraries", list(cursor, () -> {
            var entry = strings(cursor, "provider"); entry.put("symbols", texts(cursor)); entry.putAll(strings(cursor, "compiler", "compilerSha256"));
            entry.put("arguments", texts(cursor)); return entry;
        }));
        result.put("unresolved", texts(cursor));
        result.put("argumentBridges", list(cursor, () -> {
            var entry = strings(cursor, "profile", "source", "sourceSha256", "inputBitcodeSha256");
            entry.put("definitions", list(cursor, () -> texts(cursor))); return entry;
        }));
        return result;
    }
    private Map<String,Object> sourceIdentity(CoreCompactCursor cursor) throws Throwable {
        var result = map();
        field(cursor, result, "id", () -> text(cursor)); field(cursor, result, "depends", () -> texts(cursor));
        for (String key : List.of("type", "style", "pkg-name", "pkg-version")) field(cursor, result, key, () -> text(cursor));
        field(cursor, result, "flags", () -> {
            var flags = new LinkedHashMap<String,Boolean>();
            int count = cursor.count();
            for (int i = 0; i < count; i++) {
                String name = text(cursor);
                require(flags.putIfAbsent(name, cursor.readBoolean()) == null, "Duplicate compact native source flag");
            }
            return flags;
        });
        for (String key : List.of("component-name", "pkg-src-sha256", "pkg-cabal-sha256")) field(cursor, result, key, () -> text(cursor));
        return result;
    }
    private Map<String,Object> nativeDependency(CoreCompactCursor cursor) throws Throwable {
        var result = strings(cursor, "profile", "unit");
        result.put("sourceIdentity", sourceIdentity(cursor)); result.putAll(strings(cursor, "registration", "registrationSha256"));
        result.put("archives", list(cursor, () -> {
            var entry = strings(cursor, "path", "sha256"); entry.put("members", list(cursor, () -> strings(cursor, "name", "sha256"))); return entry;
        }));
        result.put("translationUnits", list(cursor, () -> {
            var receipt = strings(cursor, "root", "object", "objectSha256", "bitcode", "target");
            receipt.put("inputs", compileInput(cursor)); return map("receipt", receipt, "bitcodeSha256", text(cursor));
        }));
        return result;
    }
    private Map<String,Object> nativeArchive(CoreCompactCursor cursor) throws Throwable {
        var result = map("schema", cursor.unsigned());
        result.putAll(strings(cursor, "profile", "execution", "unit", "module"));
        result.put("unsupportedImports", list(cursor, () -> emitted(cursor)));
        field(cursor, result, "unclassifiedReason", () -> text(cursor));
        result.put("unresolvedSymbols", texts(cursor)); field(cursor, result, "artifact", () -> nativeLink(cursor));
        field(cursor, result, "conflictingImports", () -> list(cursor, () -> emitted(cursor)));
        field(cursor, result, "entryResolution", () -> {
            var entry = map("schema", cursor.unsigned());
            entry.putAll(strings(cursor, "profile", "inputBitcodeSha256"));
            entry.put("entries", list(cursor, () -> {
                var child = strings(cursor, "entry", "bitcodeSha256"); child.put("unresolved", texts(cursor)); return child;
            }));
            entry.put("outputBitcodeSha256", text(cursor)); entry.put("unresolved", texts(cursor)); return entry;
        });
        return result;
    }
    private static LinkedHashMap<String,Object> map(Object... entries) {
        var result = new LinkedHashMap<String,Object>();
        for (int i = 0; i < entries.length; i += 2) result.put((String) entries[i], entries[i + 1]);
        return result;
    }
    private static ArrayList<Object> values(Object... values) { return new ArrayList<>(Arrays.asList(values)); }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
    private static IllegalStateException error(String message) { return new IllegalStateException(message); }
    @SuppressWarnings("unchecked") private static <T,E extends Throwable> T rethrow(Throwable failure) throws E { throw (E) failure; }
    // Wire order, independently fixed by compact-core-format.md.
    private static final List<String> LAYOUT_NUMBERS = List.of(
            "infoTableBytes", "infoTablePtrsOffset", "infoTablePtrsBytes", "infoTableNptrsOffset", "infoTableNptrsBytes", "infoTableTypeOffset",
            "infoTableTypeBytes", "infoTableSrtOffset", "infoTableSrtBytes", "infoProvEntBytes", "infoProvBytes", "infoProvEntInfoOffset",
            "infoProvEntProvOffset", "infoProvNameOffset", "infoProvDescOffset", "infoProvDescBytes", "infoProvTyDescOffset", "infoProvLabelOffset",
            "infoProvUnitOffset", "infoProvModuleOffset", "infoProvFileOffset", "infoProvSpanOffset", "closureRetBco", "closureRetSmall",
            "closureRetBig", "closureRetFun", "closureUpdateFrame", "closureCatchFrame", "closureUnderflowFrame", "closureStopFrame",
            "closureStack", "closureAtomicallyFrame", "closureCatchRetryFrame", "closureCatchStmFrame", "closureAnnFrame", "stackHeaderBytes",
            "stackCatchHandlerBytes", "stackCatchFrameBytes", "stackCatchStmCodeBytes", "stackCatchStmHandlerBytes", "stackCatchStmFrameBytes",
            "stackUpdateeBytes", "stackUpdateFrameBytes", "stackAtomicallyCodeBytes", "stackAtomicallyResultBytes", "stackAtomicallyFrameBytes",
            "stackCatchRetryAltCodeBytes", "stackCatchRetryFirstCodeBytes", "stackCatchRetryAltBytes", "stackCatchRetryFrameBytes",
            "stackRetFunSizeBytes", "stackRetFunFunBytes", "stackRetFunPayloadBytes", "stackRetFunFrameBytes", "stackAnnPayloadBytes",
            "stackAnnFrameBytes", "stackClosurePayloadBytes");
    private static final List<String> KINDS = List.of("long", "float", "double", "address", "void", "data", "closure", "object", "vector", "unknown");
    private static final List<String> ELEMENTS = List.of("Int8ElemRep", "Int16ElemRep", "Int32ElemRep", "Int64ElemRep", "Word8ElemRep",
            "Word16ElemRep", "Word32ElemRep", "Word64ElemRep", "FloatElemRep", "DoubleElemRep");
    private static final List<String> PRIMITIVES = List.of("IntRep", "WordRep", "Int8Rep", "Int16Rep", "Int32Rep", "Int64Rep", "Word8Rep",
            "Word16Rep", "Word32Rep", "Word64Rep", "FloatRep", "DoubleRep", "AddrRep", "BoxedRep Nothing", "BoxedRep (Just Lifted)", "BoxedRep (Just Unlifted)");
    private static final List<String> LITERALS = List.of("int", "word", "int8", "int16", "int32", "int64", "word8", "word16", "word32",
            "word64", "bignat", "char", "string-bytes", "float", "double", "null-addr", "rubbish", "function-addr", "data-addr");
}
