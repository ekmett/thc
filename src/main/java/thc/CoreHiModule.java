// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.math.BigInteger;
import java.util.*;
import java.util.function.Function;

/** Native retained-Core lowering for pinned GHC 9.14.1. Declarations and RHS
 * syntax are parsed at module admission; unsupported tags fail immediately.
 * Supported scalar and monomorphic boxed binding bodies are lowered only when demanded. */
final class CoreHiModule {
    record Type(String name, List<Type> arguments) {
        static Type scalar(String primitive) { return new Type(primitive, List.of()); }
        static Type fun(Type argument, Type result) { return new Type("->", List.of(argument, result)); }
        boolean function() { return name.equals("->"); }
        Type result() { return arguments.get(1); }
    }
    private record Constructor(Type result, List<Type> fields, Map<String,Object> metadata) {}
    private record Info(int arity, List<Boolean> marks, Integer join) {}
    private record Binder(String name, Type type, Info info, int offset) {}
    private record Expr(int tag, List<Object> fields, int offset) {}
    private record Alt(int tag, Object discriminator, List<String> binders, Expr rhs) {}
    private record Group(boolean recursive, List<Definition> definitions) {}
    private record Definition(Binder binder, Expr rhs) {}
    private record Variable(String id, Type type) {}
    private record Lowered(List<Object> expression, Type type) {}
    private record Literal(String kind, String value, Type type) {}
    private final CoreHiReader reader;
    private final Function<String, CoreHiModule> dependencyModules;
    private final Map<Integer,Type> shared = new HashMap<>();
    private final Set<Integer> readingTypes = new HashSet<>();
    private final Set<String> dataTypes = new HashSet<>();
    private final Map<String,Constructor> constructors = new LinkedHashMap<>();
    private final Map<String,Binder> declarations = new LinkedHashMap<>();
    private final Map<String,Definition> definitions = new LinkedHashMap<>();
    private final Map<String,Variable> topScope = new HashMap<>();
    private final Map<String,Map<String,Object>> lowered = new HashMap<>();
    private final List<Group> groups = new ArrayList<>();
    private long localOrdinal;

    CoreHiModule(CoreHiReader reader) { this(reader, ignored -> null); }
    CoreHiModule(CoreHiReader reader, Function<String, CoreHiModule> dependencyModules) {
        this.reader = reader;
        this.dependencyModules = Objects.requireNonNull(dependencyModules);
        var retainedCore = reader.requireRetainedCore();
        var identity = reader.cursor(retainedCore, "retained Core identity");
        identity.require(reader.signatureOf == null && reader.sourceKind == 0, "unsupported signature or boot interface");
        var c = reader.cursor(reader.publicSections.get("declarations"), "IfaceId declarations");
        int count = c.count(4);
        for (int i = 0; i < count; i++) {
            c.unsigned(64); c.unsigned(64); // declaration fingerprint
            int offset = c.position(), tag = c.byteValue();
            c.require(tag == 0 || tag == 2, "unsupported native Core declaration tag " + tag);
            var name = CoreHiNames.read(reader, c);
            c.require(name.module().equals(reader.module), "declaration belongs to another module");
            if (tag == 2) { dataDeclaration(c, name, offset); continue; }
            var body = reader.cursor(c.lazy(), "IfaceId " + CoreHiNames.id(name));
            Type type = type(body, 0);
            List<Boolean> marks = details(body);
            Info info = info(body, 0);
            body.expectEnd();
            String id = CoreHiNames.id(name);
            c.require(declarations.putIfAbsent(id, new Binder(name.occurrence(), type,
                    new Info(info.arity, marks, null), offset)) == null, "duplicate declaration " + id);
        }
        c.expectEnd();
        var annotations = reader.cursor(reader.publicSections.get("annotations"), "Iface annotations");
        annotations.require(annotations.count(1) == 0, "unsupported native Core annotations");
        annotations.expectEnd();
        c = reader.cursor(retainedCore, "retained Core");
        count = c.count(2);
        for (int i = 0; i < count; i++) groups.add(group(c, true, 0));
        if (c.optional()) {
            c.require(c.string().isEmpty() && c.string().isEmpty(), "unsupported retained foreign source/header");
            c.require(c.count(1) == 0, "unsupported retained native initializers");
            c.require(c.count(1) == 0, "unsupported retained native finalizers");
        }
        c.require(c.count(1) == 0, "unsupported retained foreign files; native Core scalar loader has no foreign transport");
        c.expectEnd();
        // Reserve every group before any body or dependency type is resolved.
        for (var group : groups) for (var definition : group.definitions) {
            Binder binder = definition.binder;
            String id = prefix() + binder.name;
            c.require(definitions.putIfAbsent(id, definition) == null, "duplicate retained binder " + id);
            c.require(topScope.putIfAbsent(binder.name, new Variable(id, binder.type)) == null, "duplicate retained local name " + binder.name);
        }
    }

    // Dependency resolution occurs only after admission has reserved every local declaration.
    private CoreHiModule owner(String id) {
        if (definitions.containsKey(id) || declarations.containsKey(id) || constructors.containsKey(id) || dataTypes.contains(id)) return this;
        return dependencyModules.apply(id);
    }
    private String prefix() { return reader.module.unit() + ":" + reader.module.name() + "."; }
    Map<String,Object> metadata() {
        return map("schema", 1L, "ghc", "9.14.1", "unit", reader.module.unit(), "module", reader.module.name(),
                "boundary", "optimized-Core-after-Tidy-before-CorePrep", "bindings", List.of(), "constructors", constructors.values().stream().map(Constructor::metadata).toList());
    }
    boolean containsSymbol(String id) { return definitions.containsKey(id); }
    Set<String> bindingIds() { return Collections.unmodifiableSet(definitions.keySet()); }
    Type signature(String id) {
        var definition = definitions.get(id);
        var declaration = declarations.get(id);
        if (definition != null) return definition.binder.type;
        if (declaration != null) return declaration.type;
        var constructor = constructors.get(id);
        if (constructor == null) return null;
        Type signature = constructor.result;
        for (int i = constructor.fields.size() - 1; i >= 0; i--) signature = Type.fun(constructor.fields.get(i), signature);
        return signature;
    }
    Map<String,Object> binding(String id) {
        var definition = definitions.get(id);
        if (definition == null) return null;
        return lowered.computeIfAbsent(id, ignored -> binding(definition, new Variable(id, definition.binder.type), topScope));
    }

    private void dataDeclaration(CoreHiReader.Cursor c, CoreHiReader.ExternalName name, int offset) {
        c.require(name.namespace() == 3 && dataTypes.add(CoreHiNames.id(name)), "invalid or duplicate data type");
        c.require(c.count(1) == 0, "unsupported data type parameters");
        Type kind = type(c, 0);
        Type lifted = new Type("ghc-internal:GHC.Internal.Types.Lifted", List.of());
        Type boxed = new Type("ghc-internal:GHC.Internal.Types.BoxedRep", List.of(lifted));
        Type liftedRep = new Type("ghc-internal:GHC.Internal.Types.LiftedRep", List.of());
        c.require(kind.equals(new Type("ghc-internal:GHC.Internal.Types.Type", List.of())) ||
                kind.equals(new Type("ghc-internal:GHC.Internal.Prim.TYPE", List.of(boxed))) ||
                kind.equals(new Type("ghc-internal:GHC.Internal.Prim.TYPE", List.of(liftedRep))), "unsupported data result kind " + kind);
        c.require(!c.optional(), "unsupported data C type");
        c.require(c.count(1) == 0 && c.count(1) == 0, "unsupported data roles or context");
        c.require(c.byteValue() == 1, "unsupported data type form (requires ordinary boxed data)");
        int count = c.count(1); c.require(count > 0, "unsupported empty native data type");
        // GHC allocates tags by declaration order, starting at fIRST_TAG = 1.
        for (int i = 0; i < count; i++) dataConstructor(c, name, offset, i + 1);
        c.require(!bool(c) && c.byteValue() == 0, "unsupported GADT or data family parent");
    }

    private void dataConstructor(CoreHiReader.Cursor c, CoreHiReader.ExternalName name, int offset, int tag) {
        var con = CoreHiNames.read(reader, c);
        c.require(con.module().equals(reader.module) && con.namespace() == 1, "invalid data constructor identity");
        c.require(!bool(c), "unsupported data constructor wrapper");
        bool(c); // infix printing only
        c.require(c.count(1) == 0 && c.count(1) == 0 && c.count(1) == 0 && c.count(1) == 0,
                "unsupported existential, user type binders, equalities or constructor context");
        int arity = c.count(2);
        var fields = new ArrayList<Type>(arity); var representations = new ArrayList<Map<String,Object>>(arity);
        var primitiveReps = new ArrayList<Object>(arity);
        for (int i = 0; i < arity; i++) {
            type(c, 0); // multiplicity
            Type field = type(c, 0);
            // Reject wider fields before owner-aware representation lookup can resolve a dependency.
            c.require(scalar(field), "unsupported constructor field type " + field);
            var proof = rep(field, true, new Expr(-1, List.of(), offset));
            fields.add(field); representations.add(proof); primitiveReps.add(proof.get("primReps"));
        }
        c.require(c.count(1) == 0, "unsupported record field labels");
        int strictCount = c.count(1); c.require(strictCount == 0 || strictCount == arity, "constructor strictness count mismatch");
        var strict = new ArrayList<Boolean>(arity);
        for (int i = 0; i < arity; i++) {
            int bang = strictCount == 0 ? 0 : c.byteValue();
            c.require(bang <= 1, "unsupported unpacked constructor field"); strict.add(bang == 1);
        }
        int sourceCount = c.count(2); c.require(sourceCount == 0 || sourceCount == arity, "constructor source strictness count mismatch");
        for (int i = 0; i < sourceCount; i++) {
            c.require(c.byteValue() <= 2 && c.byteValue() <= 2, "invalid constructor source bang");
        }
        String id = CoreHiNames.id(con);
        var metadata = map("id", id, "name", con.occurrence(), "arity", (long) arity, "tag", (long) tag, "kind", "boxed",
                "strictFields", List.copyOf(strict), "fieldLifted", Collections.nCopies(arity, false),
                "fieldReps", primitiveReps, "fieldTypes", representations);
        c.require(constructors.putIfAbsent(id, new Constructor(new Type(CoreHiNames.id(name), List.of()), List.copyOf(fields), metadata)) == null,
                "duplicate data constructor " + id);
    }

    private Type type(CoreHiReader.Cursor c, int depth) {
        c.require(depth < 256, "type nesting exceeds native Core limit");
        int tag = c.byteValue();
        if (tag == 99) {
            long raw = c.unsigned(32);
            c.require(raw < reader.sharedTypes.size(), "shared type index out of range");
            int index = (int) raw;
            Type known = shared.get(index);
            if (known != null) return known;
            c.require(readingTypes.add(index), "cyclic shared type");
            var entry = reader.cursor(reader.sharedTypes.get(index), "shared type " + index);
            Type result = type(entry, depth + 1);
            entry.expectEnd();
            readingTypes.remove(index);
            shared.put(index, result);
            return result;
        }
        return switch (tag) {
            case 0 -> {
                Binder binder = lambdaBinder(c, depth + 1);
                c.require(c.byteValue() <= 2, "invalid forall visibility");
                yield new Type("forall:" + binder.name, List.of(binder.type, type(c, depth + 1)));
            }
            case 1 -> new Type("type-variable:" + reader.fastString(c), List.of());
            case 2 -> new Type("type-application", List.of(type(c, depth + 1), type(c, depth + 1)));
            case 3 -> {
                int flag = c.byteValue(); c.require(flag <= 3, "invalid function type flag");
                type(c, depth + 1); // multiplicity has no runtime storage
                Type argument = type(c, depth + 1), result = type(c, depth + 1);
                c.require(flag == 0, "unsupported constraint function type");
                yield Type.fun(argument, result);
            }
            case 5 -> {
                var name = CoreHiNames.read(reader, c);
                bool(c); // promotion, retained for printing by GHC
                int sort = c.byteValue();
                c.require(sort == 0, "unsupported native Core type constructor sort " + sort);
                int count = c.count(2);
                var arguments = new ArrayList<Type>(count);
                for (int i = 0; i < count; i++) {
                    arguments.add(type(c, depth + 1));
                    c.require(c.byteValue() <= 2, "invalid type argument visibility");
                }
                String primitive = scalarTyCon(name);
                yield new Type(primitive != null ? primitive : CoreHiNames.id(name), List.copyOf(arguments));
            }
            default -> throw unsupported(c, "type tag " + tag);
        };
    }
    private static String scalarTyCon(CoreHiReader.ExternalName name) {
        if (!name.module().equals(new CoreHiReader.ModuleId("ghc-internal", "GHC.Internal.Prim")) || name.namespace() != 3) return null;
        return switch (name.occurrence()) {
            case "Int#" -> "IntRep"; case "Word#" -> "WordRep";
            case "Int8#" -> "Int8Rep"; case "Int16#" -> "Int16Rep"; case "Int32#" -> "Int32Rep"; case "Int64#" -> "Int64Rep";
            case "Word8#" -> "Word8Rep"; case "Word16#" -> "Word16Rep"; case "Word32#" -> "Word32Rep"; case "Word64#" -> "Word64Rep";
            default -> null;
        };
    }
    private List<Boolean> details(CoreHiReader.Cursor c) {
        int tag = c.byteValue();
        if (tag == 0) return null;
        if (tag != 2) throw unsupported(c, "IdDetails tag " + tag);
        int count = c.count(1);
        var marks = new ArrayList<Boolean>(count);
        for (int i = 0; i < count; i++) marks.add(bool(c));
        return List.copyOf(marks);
    }
    private Info info(CoreHiReader.Cursor c, int depth) {
        c.require(depth < 256, "IdInfo nesting exceeds native Core limit");
        int arity = 0, count = c.count(1);
        for (int i = 0; i < count; i++) switch (c.byteValue()) {
            case 0 -> arity = c.count(0);
            case 1 -> { int divergence = c.byteValue(); c.require(divergence <= 2, "invalid demand divergence");
                int demands = c.count(1); for (int j = 0; j < demands; j++) demand(c, depth + 1); }
            case 2 -> {
                bool(c);
                int tag = c.byteValue();
                c.require(tag == 0, "unsupported dictionary unfolding");
                c.require(c.byteValue() <= 3, "invalid unfolding source");
                c.require(c.byteValue() <= 15, "invalid unfolding cache");
                int guidance = c.byteValue(); c.require(guidance <= 1, "invalid unfolding guidance");
                if (guidance == 1) { c.count(0); bool(c); bool(c); }
                expr(c, depth + 1); // retained RHS is the execution authority
            }
            case 3 -> { sourceText(c);
                int spec = c.byteValue(); c.require(spec <= 4, "invalid inline specification");
                if (spec != 0) sourceText(c);
                if (c.optional()) c.count(0);
                int activation = c.byteValue(); c.require(activation <= 4, "invalid inline activation");
                if (activation >= 3) { sourceText(c); c.signed(); }
                c.require(c.byteValue() <= 1, "invalid rule match info"); }
            case 4 -> {}
            case 6 -> { c.count(0); cpr(c, depth + 1); }
            case 7 -> { int tag = c.byteValue(); switch (tag) {
                case 0 -> c.count(0); case 1 -> { bool(c); bool(c); }
                case 2 -> CoreHiNames.read(reader, c); case 3 -> bool(c); case 4 -> {}
                default -> throw unsupported(c, "lambda-form info tag " + tag);
            } }
            case 8 -> tagInfo(c, depth + 1);
            default -> throw unsupported(c, "IdInfo item");
        }
        return new Info(arity, null, null);
    }
    private void sourceText(CoreHiReader.Cursor c) { if (c.optional()) reader.fastString(c); }
    private void demand(CoreHiReader.Cursor c, int depth) {
        int card = c.byteValue(); c.require(card <= 5, "invalid demand cardinality");
        if (card != 0 && card != 5) subDemand(c, depth + 1);
    }
    private void subDemand(CoreHiReader.Cursor c, int depth) {
        c.require(depth < 256, "demand nesting exceeds native Core limit");
        switch (c.byteValue()) {
            case 0 -> { bool(c); c.require(c.byteValue() <= 5, "invalid polymorphic demand cardinality"); }
            case 1 -> { c.require(c.byteValue() <= 5, "invalid call demand cardinality"); subDemand(c, depth + 1); }
            case 2 -> { bool(c); int count = c.count(1); for (int i = 0; i < count; i++) demand(c, depth + 1); }
            default -> throw unsupported(c, "sub-demand tag");
        }
    }
    private void cpr(CoreHiReader.Cursor c, int depth) {
        c.require(depth < 256, "CPR nesting exceeds native Core limit");
        int tag = c.byteValue(); c.require(tag <= 3, "invalid CPR tag");
        if (tag >= 2) c.count(0);
        if (tag == 3) { int count = c.count(1); for (int i = 0; i < count; i++) cpr(c, depth + 1); }
    }
    private void tagInfo(CoreHiReader.Cursor c, int depth) {
        c.require(depth < 256, "tag signature nesting exceeds native Core limit");
        int tag = c.byteValue(); c.require(tag >= 1 && tag <= 4, "invalid tag signature");
        if (tag == 2) { int count = c.count(1); for (int i = 0; i < count; i++) tagInfo(c, depth + 1); }
    }
    private Binder lambdaBinder(CoreHiReader.Cursor c, int depth) {
        int offset = c.position(), tag = c.byteValue();
        if (tag == 0) {
            type(c, depth + 1); // multiplicity
            String name = reader.fastString(c);
            return new Binder(name, type(c, depth + 1), new Info(0, null, null), offset);
        }
        c.require(tag == 1, "invalid lambda binder tag");
        return new Binder(reader.fastString(c), type(c, depth + 1), new Info(0, null, null), offset);
    }
    private Binder bindingBinder(CoreHiReader.Cursor c, boolean top, int depth) {
        int offset = c.position();
        if (top) {
            int tag = c.byteValue();
            if (tag == 1) {
                var name = CoreHiNames.read(reader, c);
                Binder declaration = declarations.get(CoreHiNames.id(name));
                c.require(declaration != null, "retained global binder has no IfaceId declaration " + CoreHiNames.id(name));
                return declaration;
            }
            c.require(tag == 0, "invalid retained top binder tag");
        }
        String name = reader.fastString(c);
        Type type = type(c, depth + 1);
        Info info = info(c, depth + 1);
        if (top) return new Binder(name, type, new Info(info.arity, details(c), null), offset);
        int join = c.byteValue(); c.require(join <= 1, "invalid join point tag");
        return new Binder(name, type, new Info(info.arity, null, join == 1 ? c.count(0) : null), offset);
    }
    private Group group(CoreHiReader.Cursor c, boolean top, int depth) {
        int tag = c.byteValue(); c.require(tag <= 1, "invalid binding group tag");
        int count = tag == 0 ? 1 : c.count(2);
        c.require(count > 0, "empty recursive binding group");
        var definitions = new ArrayList<Definition>(count);
        for (int i = 0; i < count; i++) {
            Binder binder = bindingBinder(c, top, depth + 1);
            if (top) c.require(c.byteValue() == 1, "unsupported IfUseUnfoldingRhs; retained Core must carry an explicit RHS");
            definitions.add(new Definition(binder, expr(c, depth + 1)));
        }
        return new Group(tag == 1, List.copyOf(definitions));
    }
    private Expr expr(CoreHiReader.Cursor c, int depth) {
        c.require(depth < 256, "expression nesting exceeds native Core limit");
        int offset = c.position(), tag = c.byteValue();
        List<Object> fields = switch (tag) {
            case 0 -> values(reader.fastString(c));
            case 1 -> values(type(c, depth + 1));
            case 4 -> { Binder binder = lambdaBinder(c, depth + 1); bool(c); yield values(binder, expr(c, depth + 1)); }
            case 5 -> values(expr(c, depth + 1), expr(c, depth + 1));
            case 6 -> {
                Expr scrutinee = expr(c, depth + 1); String name = reader.fastString(c);
                int count = c.count(3); var alts = new ArrayList<Alt>(count);
                c.require(count > 0, "IfaceCase has no alternatives");
                for (int i = 0; i < count; i++) {
                    int alt = c.byteValue(); c.require(alt <= 2, "invalid alternative tag");
                    Object discriminator = alt == 1 ? CoreHiNames.read(reader, c) : alt == 2 ? literal(c) : null;
                    int binders = c.count(1); var names = new ArrayList<String>(binders);
                    for (int j = 0; j < binders; j++) names.add(reader.fastString(c));
                    alts.add(new Alt(alt, discriminator, List.copyOf(names), expr(c, depth + 1)));
                }
                yield values(scrutinee, name, List.copyOf(alts));
            }
            case 7 -> values(group(c, false, depth + 1), expr(c, depth + 1));
            case 9 -> values(literal(c));
            case 11 -> values(CoreHiNames.read(reader, c));
            default -> throw unsupported(c, "expression tag " + tag);
        };
        return new Expr(tag, fields, offset);
    }
    private Literal literal(CoreHiReader.Cursor c) {
        int tag = c.byteValue();
        if (tag == 1) { c.skip(c.count(1)); return new Literal("string-bytes", null, Type.scalar("AddrRep")); }
        c.require(tag == 6, "unsupported native Core literal tag " + tag);
        int number = c.byteValue(); c.require(number >= 1 && number <= 10, "unsupported numeric literal type " + number);
        int integerTag = c.byteValue();
        c.require(integerTag <= 2, "invalid GHC Integer tag");
        BigInteger value;
        if (integerTag == 0) value = BigInteger.valueOf(c.signed());
        else {
            int size = c.count(1);
            c.require(size > 0 && size <= 8, "scalar literal magnitude exceeds 64 bits");
            byte[] magnitude = new byte[size];
            for (int i = size - 1; i >= 0; i--) magnitude[i] = (byte) c.byteValue();
            c.require(magnitude[0] != 0, "noncanonical GHC Integer magnitude");
            value = new BigInteger(1, magnitude);
            if (integerTag == 1) value = value.negate();
            c.require(value.compareTo(BigInteger.valueOf(Long.MIN_VALUE)) < 0 || value.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0,
                    "noncanonical large GHC Integer");
        }
        String[] kinds = {"", "int", "int8", "int16", "int32", "int64", "word", "word8", "word16", "word32", "word64"};
        String[] reps = {"", "IntRep", "Int8Rep", "Int16Rep", "Int32Rep", "Int64Rep", "WordRep", "Word8Rep", "Word16Rep", "Word32Rep", "Word64Rep"};
        int width = switch (number) { case 2, 7 -> 8; case 3, 8 -> 16; case 4, 9 -> 32; default -> 64; };
        BigInteger bound = BigInteger.ONE.shiftLeft(number < 6 ? width - 1 : width);
        c.require(value.compareTo(number < 6 ? bound.negate() : BigInteger.ZERO) >= 0 && value.compareTo(bound) < 0,
                "out-of-range scalar literal");
        return new Literal(kinds[number], value.toString(), Type.scalar(reps[number]));
    }

    private Map<String,Object> binding(Definition definition, Variable variable, Map<String,Variable> scope) {
        Binder binder = definition.binder;
        Lowered rhs = lower(definition.rhs, scope);
        var record = binder(binder, variable, false);
        record.put("arity", (long) binder.info.arity);
        record.put("expr", rhs.expression);
        record.put("rep", metadata(rhs.expression).get("rep"));
        var strict = new ArrayList<Boolean>();
        int lambdaCount = rhs.expression.getFirst().equals("lam") ? ((List<?>) rhs.expression.get(1)).size() : 0;
        if (binder.info.marks != null && binder.info.marks.size() > lambdaCount)
            throw error(definition.rhs, "worker CBV marks exceed value lambda prefix");
        for (int i = 0; i < lambdaCount; i++) strict.add(binder.info.marks != null && i < binder.info.marks.size() && binder.info.marks.get(i));
        String strictSource = strict.contains(true) ? "ghc-id" : "none";
        record.put("entryStrict", List.copyOf(strict)); record.put("entryStrictSource", strictSource);
        if (lambdaCount > 0) {
            var metadata = metadata(rhs.expression);
            metadata.put("entryStrict", List.copyOf(strict)); metadata.put("entryStrictSource", strictSource);
        }
        if (binder.info.join != null) {
            int join = binder.info.join;
            if (join > lambdaCount) throw error(definition.rhs, "join arity exceeds value lambda prefix");
            Type result = binder.type;
            for (int i = 0; i < join; i++) {
                if (!result.function()) throw error(definition.rhs, "join prefix has no function type");
                result = result.result();
            }
            record.put("joinValueArity", (long) join); record.put("joinResultRep", rep(result, false, definition.rhs));
        }
        return record;
    }
    private Map<String,Object> binder(Binder binder, Variable variable, boolean evaluated) {
        Expr location = new Expr(-1, List.of(), binder.offset);
        var information = map("cbvEligible", binder.info.marks != null || binder.info.join != null);
        if (binder.info.marks != null) information.put("cbvMarks", binder.info.marks);
        if (binder.info.join != null) information.put("joinArity", (long) binder.info.join);
        return map("id", variable.id, "name", binder.name, "lifted", lifted(binder.type), "coercion", false,
                "rep", rep(binder.type, evaluated || !lifted(binder.type), location), "info", information);
    }
    private Variable local(Binder binder) { return new Variable("\u0000hi-local:" + prefix() + localOrdinal++, binder.type); }
    private Lowered lower(Expr e, Map<String,Variable> scope) {
        return switch (e.tag) {
            case 0 -> {
                String name = (String) e.fields.getFirst();
                var variable = scope.get(name);
                if (variable == null) throw error(e, "unbound retained Core local " + name);
                yield expression(variable.type, e, "var", variable.id);
            }
            case 11 -> {
                var name = (CoreHiReader.ExternalName) e.fields.getFirst();
                String id = CoreHiNames.id(name);
                Type type;
                var signature = CoreHiNames.scalarSignature(name);
                if (CoreHiNames.isPrimop(name)) {
                    if (signature == null) throw error(e, "unsupported scalar primop signature " + id);
                    type = Type.scalar((String) signature.get("result"));
                    var arguments = (List<?>) signature.get("arguments");
                    for (int i = arguments.size() - 1; i >= 0; i--) type = Type.fun(Type.scalar((String) arguments.get(i)), type);
                    yield expression(type, e, "prim", name.occurrence());
                }
                var module = owner(id);
                var constructor = module == null ? null : module.constructors.get(id);
                type = module == null ? null : module.signature(id);
                if (constructor != null) yield expression(type, e, "con", id, (long) constructor.fields.size());
                if (type == null) throw error(e, "missing native Core dependency type " + id);
                yield expression(type, e, "var", id);
            }
            case 9 -> {
                var literal = (Literal) e.fields.getFirst();
                if (literal.value == null) throw error(e, "unsupported executable literal " + literal.kind);
                yield expression(literal.type, e, "lit", literal.kind, literal.value);
            }
            case 4 -> {
                var inner = new HashMap<>(scope);
                var parameters = new ArrayList<Map<String,Object>>();
                var types = new ArrayList<Type>();
                Expr body = e;
                while (body.tag == 4) {
                    Binder binder = (Binder) body.fields.getFirst();
                    Variable variable = local(binder);
                    inner.put(binder.name, variable);
                    parameters.add(binder(binder, variable, false)); types.add(binder.type);
                    body = (Expr) body.fields.get(1);
                }
                Lowered result = lower(body, inner);
                Type type = result.type;
                for (int i = types.size() - 1; i >= 0; i--) type = Type.fun(types.get(i), type);
                Lowered lambda = expression(type, e, "lam", parameters, result.expression);
                metadata(lambda.expression).put("resultRep", metadata(result.expression).get("rep"));
                yield lambda;
            }
            case 5 -> {
                var args = new ArrayList<Expr>(); Expr function = e;
                while (function.tag == 5) {
                    args.add((Expr) function.fields.get(1)); function = (Expr) function.fields.getFirst();
                }
                Collections.reverse(args);
                Lowered head = lower(function, scope); Type result = head.type;
                var arguments = new ArrayList<List<Object>>(args.size()); var lifted = new ArrayList<Boolean>(args.size());
                for (var argument : args) {
                    if (!result.function()) throw error(e, "unsupported application of a non-function scalar type");
                    Lowered value = lower(argument, scope);
                    arguments.add(value.expression); lifted.add(lifted(value.type)); result = result.result();
                }
                // Retained interfaces do not carry Core's speculation predicates.
                yield expression(result, e, "app", head.expression, arguments, lifted, false, false);
            }
            case 6 -> {
                Lowered scrutinee = lower((Expr) e.fields.getFirst(), scope);
                if (scrutinee.type.function()) throw error(e, "unsupported function case scrutinee");
                Binder binder = new Binder((String) e.fields.get(1), scrutinee.type, new Info(0, null, null), e.offset);
                Variable variable = local(binder); var inner = new HashMap<>(scope); inner.put(binder.name, variable);
                var alternatives = new ArrayList<List<Object>>(); Type result = null;
                for (var raw : (List<?>) e.fields.get(2)) {
                    Alt alt = (Alt) raw;
                    var branch = new HashMap<>(inner);
                    var parameters = new ArrayList<Map<String,Object>>();
                    var ids = new ArrayList<String>();
                    Object discriminator = null;
                    if (alt.tag == 1) {
                        String id = CoreHiNames.id((CoreHiReader.ExternalName) alt.discriminator);
                        var module = owner(id);
                        var constructor = module == null ? null : module.constructors.get(id);
                        if (constructor == null || !constructor.result.equals(scrutinee.type)) throw error(e, "unsupported data alternative " + id);
                        if (alt.binders.size() != constructor.fields.size()) throw error(e, "constructor case field count mismatch " + id);
                        discriminator = id;
                        for (int i = 0; i < alt.binders.size(); i++) {
                            Binder field = new Binder(alt.binders.get(i), constructor.fields.get(i), new Info(0, null, null), alt.rhs.offset);
                            Variable fieldVariable = local(field); branch.put(field.name, fieldVariable);
                            ids.add(fieldVariable.id); parameters.add(binder(field, fieldVariable, true));
                        }
                    } else if (!alt.binders.isEmpty()) throw error(e, "unexpected scalar alternative binders");
                    Lowered rhs = lower(alt.rhs, branch); if (result == null) result = rhs.type;
                    if (alt.tag == 2) {
                        Literal literal = (Literal) alt.discriminator;
                        if (literal.value == null) throw error(e, "unsupported case literal");
                        discriminator = values(literal.kind, literal.value);
                    }
                    alternatives.add(values(alt.tag == 0 ? "default" : alt.tag == 1 ? "data" : "lit", discriminator, ids, rhs.expression, map("binders", parameters)));
                }
                Lowered selected = expression(result, e, "case", scrutinee.expression, variable.id, alternatives);
                metadata(selected.expression).put("binder", binder(binder, variable, true));
                metadata(selected.expression).put("resultRep", rep(result, false, e));
                yield selected;
            }
            case 7 -> {
                Group group = (Group) e.fields.getFirst(); var inner = new HashMap<>(scope);
                var variables = new ArrayList<Variable>(); var names = new HashSet<String>();
                for (var definition : group.definitions) {
                    if (!names.add(definition.binder.name)) throw error(e, "duplicate local recursive binder " + definition.binder.name);
                    Variable variable = local(definition.binder); variables.add(variable); inner.put(definition.binder.name, variable);
                }
                var bindings = new ArrayList<Map<String,Object>>();
                for (int i = 0; i < group.definitions.size(); i++) bindings.add(binding(group.definitions.get(i), variables.get(i), group.recursive ? inner : scope));
                Lowered body = lower((Expr) e.fields.get(1), inner);
                yield expression(body.type, e, "let", group.recursive, bindings, body.expression);
            }
            default -> throw error(e, "unsupported executable expression tag " + e.tag);
        };
    }
    private Lowered expression(Type type, Expr origin, Object... fields) {
        var expression = values(fields);
        boolean evaluated = fields[0].equals("lam") || fields[0].equals("lit") || fields[0].equals("var") && !lifted(type);
        expression.add(map("rep", rep(type, evaluated, origin)));
        return new Lowered(expression, type);
    }
    private static boolean scalar(Type type) {
        return type.arguments.isEmpty() && switch (type.name) {
            case "IntRep", "Int8Rep", "Int16Rep", "Int32Rep", "Int64Rep", "WordRep", "Word8Rep", "Word16Rep", "Word32Rep", "Word64Rep" -> true;
            default -> false;
        };
    }
    private boolean data(Type type) {
        if (!type.arguments.isEmpty() || type.name.indexOf(':') < 0) return false;
        var module = owner(type.name);
        return module != null && module.dataTypes.contains(type.name);
    }
    private boolean lifted(Type type) { return type.function() || data(type); }
    private Map<String,Object> rep(Type type, boolean evaluated, Expr location) {
        if (type.function()) return map("kind", "closure", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", evaluated);
        if (scalar(type)) return map("kind", "long", "primReps", List.of(type.name), "evaluated", evaluated);
        if (data(type)) return map("kind", "data", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", evaluated);
        throw error(location, "unsupported native Core runtime type " + type.name);
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> metadata(List<Object> expression) {
        return (Map<String,Object>) expression.getLast();
    }
    private static boolean bool(CoreHiReader.Cursor c) {
        int tag = c.byteValue(); c.require(tag <= 1, "invalid boolean tag " + tag); return tag == 1;
    }
    private static IllegalArgumentException unsupported(CoreHiReader.Cursor c, String syntax) {
        c.require(false, "unsupported native Core " + syntax);
        throw new AssertionError();
    }
    private IllegalArgumentException error(Expr origin, String message) {
        return unsupported(reader.cursor(new CoreHiReader.Section(origin.offset, origin.offset), "Core lowering " + reader.module.name()), message);
    }
    private static LinkedHashMap<String,Object> map(Object... fields) {
        var result = new LinkedHashMap<String,Object>();
        for (int i = 0; i < fields.length; i += 2) result.put((String) fields[i], fields[i + 1]);
        return result;
    }
    private static ArrayList<Object> values(Object... fields) { return new ArrayList<>(Arrays.asList(fields)); }
}
