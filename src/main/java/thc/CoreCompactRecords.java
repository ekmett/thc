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
    private final boolean metadata;
    private final Map<String,String> blobs;
    private long bindingOffset;
    private static final Object MISSING = new Object();
    private record Shape(Map<String,Object> fields) {}
    private record StringSpan(long offset, long length) {}
    private final Map<Long,Shape> shapes = new HashMap<>();
    private final Set<Long> readingShapes = new HashSet<>();
    private final Map<StringSpan,String> strings = new HashMap<>();
    public CoreCompactRecords(CoreCompactFile file, String identity) { this(file, identity, new HashMap<>()); }
    CoreCompactRecords(CoreCompactFile file, String identity, Map<String,String> blobs) { this(file, identity, false, blobs); }
    private CoreCompactRecords(CoreCompactFile file, String identity, boolean metadata, Map<String,String> blobs) {
        this.file = file; this.identity = identity; this.metadata = metadata; this.blobs = blobs; debug = new CoreCompactDebug(file);
    }
    private Origin origin(long offset) { return new Origin(identity, offset, bindingOffset, debug); }
    private String text(CoreCompactCursor cursor) throws Throwable {
        var span = new StringSpan(cursor.unsigned(), cursor.unsigned());
        String existing = strings.get(span);
        if (existing != null) return existing;
        String value = metadata ? file.metadataString(span.offset, span.length) : file.string(span.offset, span.length);
        strings.put(span, value);
        return value;
    }
    private String ordinal(CoreCompactCursor cursor) { return "\u0000compact-local:" + cursor.unsigned(); }
    private String id(CoreCompactCursor cursor) throws Throwable {
        return id(cursor, cursor.readByte());
    }
    private String id(CoreCompactCursor cursor, int tag) throws Throwable {
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
        BindingFacts facts = bindingPrefix(cursor, false);
        String id = facts.id;
        var result = map("id", id, "name", id, "compactOrigin", origin);
        if (facts.hasCallable) result.put("callable", facts.callable);
        if (facts.hasHostSignature) result.put("hostSignature", facts.hostSignature);
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
    /** Independent callable/host prefix; absence and explicit unknown remain distinct. */
    public record BindingFacts(String id, boolean hasCallable, Object callable,
                               boolean hasHostSignature, Object hostSignature) {}

    /** Stops after identity. A legacy unframed host prefix returns null because
     * even locating its identity can require expression Shape backreferences. */
    public BindingFacts bindingFacts(long offset) {
        try {
            return file.data(offset, cursor -> {
                BindingFacts facts = bindingPrefix(cursor, true);
                if (facts != null) require(!facts.id.startsWith("\u0000compact-local:"),
                        "Compact top-level binding has local identity");
                return facts;
            });
        } catch (Throwable failure) { return rethrow(failure); }
    }
    private BindingFacts bindingPrefix(CoreCompactCursor cursor, boolean independent) throws Throwable {
        int tag = cursor.readByte();
        Object callable = MISSING, signature = MISSING;
        if (tag == 2) {
            if (independent) return null;
            require(file.header().getContainsHostSignatures(), "Compact host signature lacks header flag");
            signature = presence(cursor, () -> hostSignature(cursor));
            require(signature != MISSING, "Missing compact host signature extension");
            tag = cursor.readByte();
        } else if (tag == 3) {
            require(file.header().getContainsRecoveryFacts(), "Compact callable fact lacks header flag");
            CoreCompactCursor bounded = cursor.bounded(cursor.unsigned());
            callable = presence(bounded, () -> typeTerm(bounded));
            signature = presence(bounded, () -> hostSignature(bounded, true));
            require(callable != MISSING, "Missing compact callable extension");
            require(signature == MISSING || file.header().getContainsHostSignatures(), "Compact host signature lacks header flag");
            bounded.expectEnd();
            tag = cursor.readByte();
        }
        return new BindingFacts(id(cursor, tag), callable != MISSING, callable,
                signature != MISSING, signature);
    }
    // Same context-free lexical terms consumed by CoreHiTypes.read/readCo.
    Object typeName(CoreCompactCursor cursor) throws Throwable {
        String unit = text(cursor), owner = text(cursor);
        int namespace = cursor.readByte();
        require(namespace <= 4, "Invalid recovery Name namespace");
        Object parent = optional(cursor, () -> text(cursor));
        return Arrays.asList(unit, owner, namespace, parent, text(cursor));
    }
    private static <T> T optional(CoreCompactCursor cursor, Reader<T> read) throws Throwable {
        return switch (cursor.readByte()) {
            case 0 -> null;
            case 1 -> read.read();
            default -> throw error("Invalid recovery optional tag");
        };
    }
    Object typeBinder(CoreCompactCursor cursor) throws Throwable {
        return List.of(text(cursor), cursor.readBoolean(), typeTerm(cursor));
    }
    private static int typeEnum(CoreCompactCursor cursor, int maximum) {
        int value = cursor.readByte();
        require(value <= maximum, "Invalid recovery enumeration");
        return value;
    }
    private Object typeSort(CoreCompactCursor cursor) {
        return switch (cursor.readByte()) {
            case 0 -> List.of(0);
            case 1 -> List.of(1, cursor.unsigned(), typeEnum(cursor, 2));
            case 2 -> List.of(2, cursor.unsigned());
            case 3 -> List.of(3);
            default -> throw error("Invalid recovery TyCon sort");
        };
    }
    private List<?> typeArguments(CoreCompactCursor cursor) throws Throwable {
        return list(cursor, () -> List.of(typeEnum(cursor, 2), typeTerm(cursor)));
    }
    private static long codePoint(CoreCompactCursor cursor) {
        long value = cursor.unsigned();
        require(value <= 0x10ffff, "Invalid recovery literal code point");
        return value;
    }
    Object typeTerm(CoreCompactCursor cursor) throws Throwable {
        return switch (cursor.readByte()) {
            case 0 -> List.of("var", text(cursor));
            case 1 -> List.of("con", typeName(cursor), cursor.readBoolean(), typeSort(cursor), typeArguments(cursor));
            case 2 -> List.of("app", typeTerm(cursor), typeArguments(cursor));
            case 3 -> List.of("fun", typeEnum(cursor, 3), typeTerm(cursor), typeTerm(cursor), typeTerm(cursor));
            case 4 -> List.of("forall", typeBinder(cursor), typeEnum(cursor, 2), typeTerm(cursor));
            case 5 -> List.of("tuple", typeEnum(cursor, 2), cursor.readBoolean(), typeArguments(cursor));
            case 6 -> {
                String value = text(cursor);
                require(new BigInteger(value).toString().equals(value), "Noncanonical recovery integer");
                yield List.of("lit", 1, value);
            }
            case 7 -> List.of("lit", 2, list(cursor, () -> codePoint(cursor)));
            case 8 -> List.of("lit", 3, codePoint(cursor));
            case 9 -> List.of("cast", typeTerm(cursor), coTerm(cursor));
            case 10 -> List.of("coercion", coTerm(cursor));
            default -> throw error("Unknown recovery type term");
        };
    }
    private int typeRole(CoreCompactCursor cursor) { return typeEnum(cursor, 2) + 1; }
    private Object coTerm(CoreCompactCursor cursor) throws Throwable {
        return switch (cursor.readByte()) {
            case 0 -> List.of("refl", typeTerm(cursor));
            case 1 -> Arrays.asList("grefl", typeRole(cursor), typeTerm(cursor), optional(cursor, () -> coTerm(cursor)));
            case 2 -> List.of("funco", typeRole(cursor), coTerm(cursor), coTerm(cursor), coTerm(cursor));
            case 3 -> List.of("conco", typeRole(cursor), typeName(cursor), cursor.readBoolean(), typeSort(cursor), list(cursor, () -> coTerm(cursor)));
            case 4 -> List.of("appco", coTerm(cursor), coTerm(cursor));
            case 5 -> List.of("forallco", typeBinder(cursor), typeEnum(cursor, 2), typeEnum(cursor, 2), coTerm(cursor), coTerm(cursor));
            case 6 -> List.of("varco", text(cursor));
            case 7 -> {
                Object provenance = switch (cursor.readByte()) {
                    case 0 -> List.of(1);
                    case 1 -> List.of(2);
                    case 2 -> List.of(3, text(cursor));
                    default -> throw error("Invalid recovery coercion provenance");
                };
                yield List.of("univ", provenance, typeRole(cursor), typeTerm(cursor), typeTerm(cursor), list(cursor, () -> coTerm(cursor)));
            }
            case 8 -> List.of("sym", coTerm(cursor));
            case 9 -> List.of("trans", coTerm(cursor), coTerm(cursor));
            case 10 -> {
                Object selector = switch (cursor.readByte()) {
                    case 0 -> List.of(0, cursor.unsigned(), typeRole(cursor));
                    case 1 -> List.of(1);
                    case 2 -> List.of(2);
                    case 3 -> List.of(3);
                    case 4 -> List.of(4);
                    default -> throw error("Invalid recovery coercion selector");
                };
                yield List.of("sel", selector, coTerm(cursor));
            }
            case 11 -> List.of("lr", typeEnum(cursor, 1), coTerm(cursor));
            case 12 -> List.of("inst", coTerm(cursor), coTerm(cursor));
            case 13 -> List.of("kind", coTerm(cursor));
            case 14 -> List.of("sub", coTerm(cursor));
            case 15 -> {
                Object rule = switch (cursor.readByte()) {
                    case 0 -> List.of(0, text(cursor));
                    case 1 -> List.of(1, typeName(cursor));
                    case 2 -> List.of(2, typeName(cursor), cursor.unsigned());
                    default -> throw error("Invalid recovery axiom rule");
                };
                yield List.of("axiom", rule, list(cursor, () -> coTerm(cursor)));
            }
            default -> throw error("Unknown recovery coercion term");
        };
    }
    Object typeParameter(CoreCompactCursor cursor) throws Throwable {
        Object binder = typeBinder(cursor);
        Object visibility = switch (cursor.readByte()) {
            case 0 -> List.of(0);
            case 1 -> List.of(1, typeEnum(cursor, 2));
            default -> throw error("Invalid recovery parameter visibility");
        };
        return List.of(binder, visibility);
    }
    Map<String,Object> typeAxiom(CoreCompactCursor cursor) throws Throwable {
        return map("name", typeName(cursor), "tycon", typeName(cursor), "role", typeRole(cursor),
                "branches", list(cursor, () -> map("binders", list(cursor, () -> typeBinder(cursor)),
                        "roles", list(cursor, () -> typeRole(cursor)), "lhs", list(cursor, () -> typeTerm(cursor)), "rhs", typeTerm(cursor))));
    }

    private Map<String,Object> hostSignature(CoreCompactCursor cursor) throws Throwable {
        return hostSignature(cursor, false);
    }
    private Map<String,Object> hostSignature(CoreCompactCursor cursor, boolean inline) throws Throwable {
        return map("inputs", list(cursor, () -> hostType(cursor, inline)), "result", hostType(cursor, inline));
    }
    private Map<String,Object> hostType(CoreCompactCursor cursor, boolean inline) throws Throwable {
        return map("rep", rep(cursor, inline), "carriers", list(cursor, () -> switch (cursor.readByte()) {
            case 0 -> null;
            case 1 -> "object";
            case 2 -> "interop-library";
            default -> throw error("Invalid compact host carrier");
        }));
    }
    private Map<String,Object> demand(CoreCompactCursor cursor) throws Throwable { return map("arity", cursor.unsigned(), "strictArgs", list(cursor, cursor::readBoolean)); }
    private Map<String,Object> family(CoreCompactCursor cursor) throws Throwable { return map("typeConstructor", text(cursor), "constructors", texts(cursor)); }
    private Map<String,Object> tagFamily(CoreCompactCursor cursor) throws Throwable {
        var result = family(cursor);
        result.put("smallFamilyLimit", cursor.unsigned()); result.put("smallFamily", cursor.readBoolean());
        return result;
    }
    private Map<String,Object> foreign(CoreCompactCursor cursor, boolean inline) throws Throwable {
        long schema = cursor.unsigned();
        require(schema == 1 || schema == 2, "Unsupported compact Core foreign-call schema");
        var result = map("schema", schema);
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
        if (schema == 2) field(cursor, result, "argumentTypes", () -> list(cursor, () -> element(cursor, () -> text(cursor))));
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
        require(tag <= 9, "Invalid compact Core expression tag: " + tag);
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
            case 9 -> values("unsupported", text(cursor));
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
        var ids = new ArrayList<Object>();
        for (var binder : binders) ids.add(binder.get("id"));
        return values(kind, discriminator, Collections.unmodifiableList(ids), expression(cursor), map("binders", binders));
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
            case 15 -> "0"; case 16 -> null; case 17, 18, 19 -> text(cursor);
            default -> throw error("Unreachable compact literal tag");
        };
        return values(kind, value);
    }
    public Map<String,Object> constructor(CoreCompactCursor cursor) {
        try {
            String id = text(cursor);
            // Reconstruct the JSON adapter's occurrence without reading debug tables.
            // Strip module components, not the last dot: an operator may contain dots.
            String name = id.substring(id.indexOf(':') + 1);
            int dot;
            while (!name.isEmpty() && Character.isUpperCase(name.charAt(0)) && (dot = name.indexOf('.')) >= 0)
                name = name.substring(dot + 1);
            var result = map("id", id, "name", name, "arity", cursor.unsigned(), "tag", cursor.unsigned(),
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
        return new CoreCompactRecords(file, identity, true, blobs).readHeader();
    }
    private Map<String,Object> readHeader() {
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
                int extensions = 0;
                while (cursor.getRemaining() != 0) {
                    int tag = cursor.readByte();
                    require(tag == 1 || tag == 2, "Invalid compact Core metadata extension");
                    require((extensions & (1 << tag)) == 0, "Duplicate compact Core metadata extension");
                    extensions |= 1 << tag;
                    if (tag == 1) {
                        field(cursor, result, "roots", () -> texts(cursor));
                        field(cursor, result, "sourceModules", () -> texts(cursor));
                        field(cursor, result, "missingDefinitions", () -> list(cursor, () -> strings(cursor, "id", "type", "reason")));
                        result.put("bindingOrigins", list(cursor, () -> {
                            var binding = map("id", text(cursor));
                            field(cursor, binding, "origin", () -> text(cursor));
                            field(cursor, binding, "originModule", () -> text(cursor));
                            return binding;
                        }));
                    } else {
                        var policy = map();
                        int defaultBackend = cursor.readByte();
                        require(defaultBackend <= 2, "Invalid compact Core default backend");
                        if (defaultBackend != 0) policy.put("default", defaultBackend == 1 ? "ast" : "bytecode");
                        var bindings = new LinkedHashMap<String,String>();
                        byte[] previous = null;
                        for (int count = cursor.count(); count > 0; count--) {
                            String id = text(cursor);
                            byte[] key = id.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                            require(previous == null || Arrays.compareUnsigned(previous, key) < 0, "Unsorted or duplicate compact Core backend binding");
                            int backend = cursor.readByte();
                            require(backend == 1 || backend == 2, "Invalid compact Core binding backend");
                            bindings.put(id, backend == 1 ? "ast" : "bytecode"); previous = key;
                        }
                        policy.put("bindings", bindings); result.put("backendPolicy", policy);
                    }
                }
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
        long schema = (Long) layout.get("schema");
        require(schema == 1 || schema == 2, "Unsupported GHC target layout schema");
        for (String key : LAYOUT_NUMBERS) layout.put(key, cursor.unsigned());
        if (schema == 2) for (String key : List.of("rtsFlagsBytes", "traceFlagsBytes", "rtsTraceFlagsOffset",
                "rtsTraceFlagsBytes", "traceUserOffset", "traceUserBytes")) layout.put(key, cursor.unsigned());
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
            if (Objects.equals(result.get("schema"), 2L) || Objects.equals(result.get("schema"), 3L) || Objects.equals(result.get("schema"), 4L)) result.put("addresses", list(cursor, () -> {
                var entry = map("binder", qualifiedName(cursor));
                field(cursor, entry, "header", () -> text(cursor)); entry.put("symbol", text(cursor));
                entry.put("isFunction", cursor.readBoolean()); entry.put("convention", convention(cursor));
                entry.put("declaredType", foreignType(cursor)); entry.put("normalizedType", foreignType(cursor));
                entry.put("normalizationRole", text(cursor));
                entry.put("callback", cursor.readBoolean() ? map("arguments", texts(cursor), "result", text(cursor)) : null);
                return entry;
            }));
            if (Objects.equals(result.get("schema"), 3L) || Objects.equals(result.get("schema"), 4L)) result.put("wrappers", list(cursor, () -> map(
                "binder", qualifiedName(cursor), "helper", text(cursor), "convention", convention(cursor),
                "declaredType", foreignType(cursor), "normalizedType", foreignType(cursor), "normalizationRole", text(cursor),
                "arguments", list(cursor, () -> foreignType(cursor)), "result", foreignType(cursor),
                "effect", enumeration(cursor, List.of("pure", "io")), "typeString", text(cursor))));
            if (Objects.equals(result.get("schema"), 4L)) result.put("importForeign", artifacts(cursor));
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
    private String blob(CoreCompactCursor cursor) {
        String encoded = HexFormat.of().formatHex(cursor.bytes(cursor.count()));
        return blobs.computeIfAbsent(encoded, value -> value);
    }
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
        int buildTag = cursor.readByte();
        switch (buildTag) {
            case 0 -> { }
            case 1 -> result.put("buildInputs", null);
            case 2, 3, 4 -> result.put("buildInputs", nativeBuildInputs(cursor, buildTag));
            default -> throw error("Invalid compact native build-input tag");
        }
        int extras = cursor.readByte();
        switch (extras) {
            case 0 -> { }
            case 3, 4 -> {
                field(cursor, result, "nativeLibrary", () -> map("sha256", text(cursor), "hex", blob(cursor)));
                field(cursor, result, "dataSymbols", () -> texts(cursor));
                if (extras == 4) {
                    result.put("exports", texts(cursor));
                    result.put("dependencies", list(cursor, () -> nativeComponent(cursor)));
                }
            }
            default -> throw error("Retired or invalid compact native entry metadata");
        }
        if (Objects.equals(result.get("schema"), 2L)) result.put("finalizers", texts(cursor));
        if (Objects.equals(result.get("schema"), 3L)) result.put("callSeeds", list(cursor, () -> {
            var seed = strings(cursor, "entry", "bitcodeSha256"); seed.put("bitcodeHex", blob(cursor));
            int provider = cursor.readByte();
            if (provider == 0) {
                seed.put("providerUnit", null); seed.put("providerComponentSha256", null); seed.put("providerSymbol", null);
            } else if (provider == 1) seed.putAll(strings(cursor, "providerUnit", "providerComponentSha256", "providerSymbol"));
            else throw error("Invalid native call seed provider tag");
            return seed;
        }));
        return result;
    }
    private Map<String,Object> nativeComponent(CoreCompactCursor cursor) throws Throwable {
        var result = linkPayload(cursor);
        result.put("exports", texts(cursor));
        result.put("dependencies", list(cursor, () -> nativeComponent(cursor)));
        field(cursor, result, "nativeLibrary", () -> map("sha256", text(cursor), "hex", blob(cursor)));
        return result;
    }
    private Map<String,Object> compileInput(CoreCompactCursor cursor) throws Throwable {
        var result = strings(cursor, "compiler", "clang");
        result.put("arguments", texts(cursor)); field(cursor, result, "language", () -> text(cursor));
        result.putAll(strings(cursor, "nativeTarget", "target"));
        result.put("files", list(cursor, () -> strings(cursor, "path", "sha256")));
        return result;
    }
    private Map<String,Object> nativeBuildInputs(CoreCompactCursor cursor, int version) throws Throwable {
        var result = map();
        result.put("translationUnits", list(cursor, () -> {
            int tag = cursor.readByte();
            return switch (tag) { case 0 -> compileInput(cursor); case 1 -> list(cursor, () -> compileInput(cursor)); default -> throw error("Invalid compact native compile group: " + tag); };
        }));
        result.put("providers", list(cursor, () -> {
            var entry = strings(cursor, "provider"); entry.put("symbols", texts(cursor)); entry.putAll(strings(cursor, "bitcode", "bitcodeSha256", "target"));
            entry.put("inputs", compileInput(cursor)); return entry;
        }));
        field(cursor, result, "dependencies", () -> list(cursor, () -> {
            if (version < 4) return nativeDependency(cursor, false);
            var entry = map("declaredPath", texts(cursor));
            entry.putAll(strings(cursor, "unit", "componentSha256", "bitcodeSha256")); return entry;
        }));
        result.put("nativeLibraries", list(cursor, () -> {
            var entry = strings(cursor, "provider"); entry.put("symbols", texts(cursor)); entry.putAll(strings(cursor, "compiler", "compilerSha256"));
            entry.put("arguments", texts(cursor));
            if (version >= 3) {
                field(cursor, entry, "dependencyArguments", () -> texts(cursor));
                field(cursor, entry, "objcopy", () -> text(cursor));
                field(cursor, entry, "objcopySha256", () -> text(cursor));
                field(cursor, entry, "objcopyArguments", () -> list(cursor, () -> texts(cursor)));
            }
            return entry;
        }));
        result.put("unresolved", texts(cursor));
        result.put("argumentBridges", list(cursor, () -> {
            var entry = strings(cursor, "profile", "source", "sourceSha256", "inputBitcodeSha256");
            entry.put("definitions", list(cursor, () -> texts(cursor))); return entry;
        }));
        if (version == 4) field(cursor, result, "nativeProduct", () -> nativeDependency(cursor, true));
        return result;
    }
    private Map<String,Object> sourceIdentity(CoreCompactCursor cursor, boolean location) throws Throwable {
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
        if (location) field(cursor, result, "pkg-src", () -> {
            var source = strings(cursor, "type"); field(cursor, source, "path", () -> text(cursor));
            field(cursor, source, "repo", () -> strings(cursor, "type", "uri")); return source;
        });
        return result;
    }
    private Map<String,Object> nativeDependency(CoreCompactCursor cursor, boolean location) throws Throwable {
        var result = strings(cursor, "profile", "unit");
        result.put("sourceIdentity", sourceIdentity(cursor, location)); result.putAll(strings(cursor, "registration", "registrationSha256"));
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
        require(cursor.readByte() == 0, "Retired compact native entry-resolution metadata");
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
            "word64", "bignat", "char", "string-bytes", "float", "double", "null-addr", "rubbish", "function-addr", "data-addr", "unsupported");
}
