// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.math.BigInteger;
import java.util.*;
import java.util.function.Function;
import thc.runtime.CoreFloatingLiteral;

/** Native retained-Core lowering for pinned GHC 9.14.1. Declarations and RHS
 * syntax are parsed at module admission; unsupported tags fail immediately.
 * Supported scalar, fixed-lifted polymorphic and ordinary boxed bodies are lowered only when demanded. */
final class CoreHiModule {
    // Internal type forms cannot collide with canonical unit:module names.
    private static final String FORALL = "\u0000hi-forall:", TYPE_VARIABLE = "\u0000hi-type-variable:", ABSTRACT_LIFTED = "\u0000hi-lifted-variable:";
    record Type(String name, List<Type> arguments) {
        static Type scalar(String primitive) { return new Type(primitive, List.of()); }
        static Type fun(Type argument, Type result) { return new Type("->", List.of(argument, result)); }
        boolean function() { return name.equals("->"); }
        boolean forall() { return name.startsWith(FORALL); }
        boolean abstractLifted() { return name.startsWith(ABSTRACT_LIFTED); }
        Type result() { return arguments.get(1); }
    }
    private record Constructor(Type result, List<Binder> parameters, List<Type> fields, Map<String,Object> metadata, int offset) {}
    private record Info(int arity, List<Boolean> marks, Integer join) {}
    private record Binder(String name, Type type, Info info, int offset, boolean typeVariable) {}
    private record Expr(int tag, List<Object> fields, int offset) {}
    private record Alt(int tag, Object discriminator, List<String> binders, Expr rhs) {}
    private record Group(boolean recursive, List<Definition> definitions) {}
    private record Definition(Binder binder, Expr rhs) {}
    private record Variable(String id, Type type) {}
    private record Lowered(List<Object> expression, Type type) {}
    private record Literal(String kind, Object value, Type type) {}
    private final CoreHiReader reader;
    private final Function<String, CoreHiModule> dependencyModules;
    private final Map<Integer,Type> shared = new HashMap<>();
    private final Set<Integer> readingTypes = new HashSet<>();
    private final Map<String,List<Binder>> dataTypes = new HashMap<>();
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
            c.require(!CoreHiNames.id(name).equals(CoreUnitDirectory.MAIN_ALIAS), "unsupported native Core main alias obligations");
            c.require(name.module().equals(reader.module), "declaration belongs to another module");
            if (tag == 2) { dataDeclaration(c, name, offset); continue; }
            var body = reader.cursor(c.lazy(), "IfaceId " + CoreHiNames.id(name));
            Type type = type(body, 0);
            List<Boolean> marks = details(body);
            Info info = info(body, 0);
            body.expectEnd();
            String id = CoreHiNames.id(name);
            c.require(declarations.putIfAbsent(id, new Binder(name.occurrence(), type,
                    new Info(info.arity, marks, null), offset, false)) == null, "duplicate declaration " + id);
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
        if (definitions.containsKey(id) || declarations.containsKey(id) || constructors.containsKey(id) || dataTypes.containsKey(id)) return this;
        return dependencyModules.apply(id);
    }
    private String prefix() { return reader.module.unit() + ":" + reader.module.name() + "."; }
    Map<String,Object> metadata() {
        return map("schema", 1L, "ghc", "9.14.1", "unit", reader.module.unit(), "module", reader.module.name(),
                "boundary", "optimized-Core-after-Tidy-before-CorePrep", "bindings", List.of(), "constructors", constructors.values().stream().map(this::constructorMetadata).toList());
    }
    private Map<String,Object> constructorMetadata(Constructor constructor) {
        // Sources reserves the parsed module before this lookup; layout never follows other constructor fields.
        var scope = new HashMap<String,Type>();
        for (int i = 0; i < constructor.parameters.size(); i++)
            scope.put(constructor.parameters.get(i).name, Type.scalar(ABSTRACT_LIFTED + constructor.result.name + "." + i));
        var representations = new ArrayList<Map<String,Object>>(constructor.fields.size());
        var primitiveReps = new ArrayList<Object>(constructor.fields.size());
        var fieldLifted = new ArrayList<Boolean>(constructor.fields.size());
        Expr location = new Expr(-1, List.of(), constructor.offset);
        for (int i = 0; i < constructor.fields.size(); i++) {
            try {
                Type field = resolve(constructor.fields.get(i), scope, location, false);
                boolean lifted = field.abstractLifted() || data(field);
                if (!scalar(field) && !lifted) throw new IllegalArgumentException("unsupported constructor field type " + field);
                var proof = rep(field, !lifted, location);
                fieldLifted.add(lifted); representations.add(proof); primitiveReps.add(proof.get("primReps"));
            } catch (IllegalArgumentException failure) {
                throw error(location, "constructor " + constructor.metadata.get("id") + " field " + i + ": " + failure.getMessage());
            }
        }
        var metadata = new LinkedHashMap<>(constructor.metadata);
        metadata.put("fieldLifted", List.copyOf(fieldLifted));
        metadata.put("fieldReps", List.copyOf(primitiveReps));
        metadata.put("fieldTypes", List.copyOf(representations));
        return metadata;
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
        for (int i = constructor.parameters.size() - 1; i >= 0; i--) {
            Binder parameter = constructor.parameters.get(i);
            signature = new Type(FORALL + parameter.name, List.of(parameter.type, signature));
        }
        return signature;
    }
    Map<String,Object> binding(String id) {
        var definition = definitions.get(id);
        if (definition == null) return null;
        return lowered.computeIfAbsent(id, ignored -> binding(definition, new Variable(id, definition.binder.type), topScope, Map.of()));
    }

    private void dataDeclaration(CoreHiReader.Cursor c, CoreHiReader.ExternalName name, int offset) {
        c.require(name.namespace() == 3 && !dataTypes.containsKey(CoreHiNames.id(name)), "invalid or duplicate data type");
        int parameterCount = c.count(2);
        var parameters = new ArrayList<Binder>(parameterCount); var names = new HashSet<String>();
        for (int i = 0; i < parameterCount; i++) {
            Binder parameter = lambdaBinder(c, 0);
            c.require(parameter.typeVariable && liftedKind(parameter.type) && names.add(parameter.name),
                    "unsupported or duplicate data type parameter");
            int visibility = c.byteValue(); c.require(visibility <= 1, "invalid data binder visibility");
            if (visibility == 1) c.require(c.byteValue() <= 2, "invalid named data binder visibility");
            parameters.add(parameter);
        }
        dataTypes.put(CoreHiNames.id(name), List.copyOf(parameters));
        Type kind = type(c, 0);
        c.require(liftedKind(kind), "unsupported data result kind " + kind);
        c.require(!c.optional(), "unsupported data C type");
        int roles = c.count(1); c.require(roles == parameterCount, "data role count mismatch");
        for (int i = 0; i < roles; i++) {
            int role = c.byteValue(); c.require(role >= 1 && role <= 3, "invalid data role");
        }
        c.require(c.count(1) == 0, "unsupported data context");
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
        c.require(c.count(1) == 0, "unsupported existential constructor binders");
        var parameters = dataTypes.get(CoreHiNames.id(name));
        int universals = c.count(2); c.require(universals == parameters.size(), "unsupported constructor type binders");
        for (int i = 0; i < universals; i++) {
            Binder parameter = lambdaBinder(c, 0), expected = parameters.get(i);
            c.require(parameter.typeVariable && parameter.name.equals(expected.name) && parameter.type.equals(expected.type),
                    "unsupported reordered or refined constructor type binder");
            c.require(c.byteValue() <= 2, "invalid constructor forall visibility");
        }
        c.require(c.count(1) == 0 && c.count(1) == 0, "unsupported constructor equalities or context");
        int arity = c.count(2);
        var fields = new ArrayList<Type>(arity);
        for (int i = 0; i < arity; i++) {
            type(c, 0); // multiplicity
            fields.add(type(c, 0));
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
                "strictFields", List.copyOf(strict));
        c.require(constructors.putIfAbsent(id, new Constructor(new Type(CoreHiNames.id(name), parameters.stream().map(p -> Type.scalar(TYPE_VARIABLE + p.name)).toList()), parameters, List.copyOf(fields), metadata, offset)) == null,
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
                c.require(binder.typeVariable, "unsupported coercion forall binder");
                c.require(c.byteValue() <= 2, "invalid forall visibility");
                yield new Type(FORALL + binder.name, List.of(binder.type, type(c, depth + 1)));
            }
            case 1 -> new Type(TYPE_VARIABLE + reader.fastString(c), List.of());
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
            case "Int#" -> "IntRep"; case "Word#" -> "WordRep"; case "Addr#" -> "AddrRep";
            case "Float#" -> "FloatRep"; case "Double#" -> "DoubleRep";
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
            return new Binder(name, type(c, depth + 1), new Info(0, null, null), offset, false);
        }
        c.require(tag == 1, "invalid lambda binder tag");
        return new Binder(reader.fastString(c), type(c, depth + 1), new Info(0, null, null), offset, true);
    }
    private Binder bindingBinder(CoreHiReader.Cursor c, boolean top, int depth) {
        int offset = c.position();
        if (top) {
            int tag = c.byteValue();
            if (tag == 1) {
                var name = CoreHiNames.read(reader, c);
                c.require(!CoreHiNames.id(name).equals(CoreUnitDirectory.MAIN_ALIAS), "unsupported native Core main alias obligations");
                Binder declaration = declarations.get(CoreHiNames.id(name));
                c.require(declaration != null, "retained global binder has no IfaceId declaration " + CoreHiNames.id(name));
                return declaration;
            }
            c.require(tag == 0, "invalid retained top binder tag");
        }
        String name = reader.fastString(c);
        Type type = type(c, depth + 1);
        Info info = info(c, depth + 1);
        if (top) return new Binder(name, type, new Info(info.arity, details(c), null), offset, false);
        int join = c.byteValue(); c.require(join <= 1, "invalid join point tag");
        return new Binder(name, type, new Info(info.arity, null, join == 1 ? c.count(0) : null), offset, false);
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
            case 10 -> throw unsupported(c, "foreign call; native interface foreign transport is not implemented");
            case 11 -> {
                var name = CoreHiNames.read(reader, c);
                c.require(!CoreHiNames.id(name).equals(CoreUnitDirectory.MAIN_ALIAS), "unsupported native Core main alias obligations");
                c.require(!CoreHiNames.isPrimop(name) || !Set.of("prompt#", "control0#").contains(name.occurrence()),
                        "unsupported native Core delimited-control obligations");
                yield values(name);
            }
            default -> throw unsupported(c, "expression tag " + tag);
        };
        return new Expr(tag, fields, offset);
    }
    private Literal literal(CoreHiReader.Cursor c) {
        int tag = c.byteValue();
        if (tag == 1) {
            int size = c.count(1);
            c.require(size <= Integer.MAX_VALUE / 2, "byte literal exceeds hexadecimal string size");
            byte[] bytes = new byte[size];
            for (int i = 0; i < size; i++) bytes[i] = (byte) c.byteValue();
            return new Literal("string-bytes", HexFormat.of().formatHex(bytes), Type.scalar("AddrRep"));
        }
        if (tag == 3 || tag == 4) {
            BigInteger numerator = integer(c), denominator = integer(c);
            c.require(denominator.signum() >= 0 || numerator.signum() == 0, "negative GHC Rational denominator");
            long bits = floatingBits(numerator, denominator, tag == 3 ? 24 : 53, tag == 3 ? 8 : 11);
            return new Literal(tag == 3 ? "float" : "double", tag == 3 ? new CoreFloatingLiteral.Single((int) bits) :
                    new CoreFloatingLiteral.Double(bits), Type.scalar(tag == 3 ? "FloatRep" : "DoubleRep"));
        }
        c.require(tag == 6, "unsupported native Core literal tag " + tag);
        int number = c.byteValue(); c.require(number >= 1 && number <= 10, "unsupported numeric literal type " + number);
        BigInteger value = integer(c);
        String[] kinds = {"", "int", "int8", "int16", "int32", "int64", "word", "word8", "word16", "word32", "word64"};
        String[] reps = {"", "IntRep", "Int8Rep", "Int16Rep", "Int32Rep", "Int64Rep", "WordRep", "Word8Rep", "Word16Rep", "Word32Rep", "Word64Rep"};
        int width = switch (number) { case 2, 7 -> 8; case 3, 8 -> 16; case 4, 9 -> 32; default -> 64; };
        BigInteger bound = BigInteger.ONE.shiftLeft(number < 6 ? width - 1 : width);
        c.require(value.compareTo(number < 6 ? bound.negate() : BigInteger.ZERO) >= 0 && value.compareTo(bound) < 0,
                "out-of-range scalar literal");
        return new Literal(kinds[number], value.toString(), Type.scalar(reps[number]));
    }

    private BigInteger integer(CoreHiReader.Cursor c) {
        int integerTag = c.byteValue();
        c.require(integerTag <= 2, "invalid GHC Integer tag");
        BigInteger value;
        if (integerTag == 0) value = BigInteger.valueOf(c.signed());
        else {
            int size = c.count(1);
            c.require(size > 0, "empty GHC Integer magnitude");
            byte[] magnitude = new byte[size];
            for (int i = size - 1; i >= 0; i--) magnitude[i] = (byte) c.byteValue();
            c.require(magnitude[0] != 0, "noncanonical GHC Integer magnitude");
            value = new BigInteger(1, magnitude);
            if (integerTag == 1) value = value.negate();
            c.require(value.compareTo(BigInteger.valueOf(Long.MIN_VALUE)) < 0 || value.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0,
                    "noncanonical large GHC Integer");
        }
        return value;
    }
    private static long floatingBits(BigInteger numerator, BigInteger denominator, int precision, int exponentBits) {
        int fraction = precision - 1, maximum = (1 << (exponentBits - 1)) - 1, minimum = 1 - maximum;
        long sign = numerator.signum() < 0 ? 1L << (fraction + exponentBits) : 0;
        long infinity = ((1L << exponentBits) - 1) << fraction;
        // GHC's rationalToFloat/Double defines denominator zero explicitly; Rational carries no signed zero.
        if (denominator.signum() == 0) return numerator.signum() == 0 ? infinity | 1L << (fraction - 1) : sign | infinity;
        if (numerator.signum() == 0) return 0;
        numerator = numerator.abs();
        int exponent = numerator.bitLength() - denominator.bitLength();
        int subnormalUnit = minimum - fraction;
        // The exact floor exponent is either this bit-length difference or one less. Bound shifts first.
        if (exponent > maximum + 1) return sign | infinity;
        if (exponent < subnormalUnit - 1) return sign;
        int comparison = exponent >= 0 ? numerator.compareTo(denominator.shiftLeft(exponent)) :
                numerator.shiftLeft(-exponent).compareTo(denominator);
        if (comparison < 0) exponent--;
        if (exponent > maximum) return sign | infinity;
        int unit = Math.max(exponent, minimum) - fraction;
        BigInteger dividend = unit < 0 ? numerator.shiftLeft(-unit) : numerator;
        BigInteger divisor = unit < 0 ? denominator : denominator.shiftLeft(unit);
        var division = dividend.divideAndRemainder(divisor);
        BigInteger mantissa = division[0];
        int rounding = division[1].shiftLeft(1).compareTo(divisor);
        if (rounding > 0 || rounding == 0 && mantissa.testBit(0)) mantissa = mantissa.add(BigInteger.ONE);
        // Subnormal units encode directly, including rounding into the smallest normal value.
        if (exponent < minimum) return sign | mantissa.longValueExact();
        if (mantissa.bitLength() > precision) { mantissa = mantissa.shiftRight(1); exponent++; }
        if (exponent > maximum) return sign | infinity;
        return sign | ((long) (exponent + maximum) << fraction) | (mantissa.longValueExact() - (1L << fraction));
    }

    private Map<String,Object> binding(Definition definition, Variable variable, Map<String,Variable> scope, Map<String,Type> typeScope) {
        Binder binder = definition.binder;
        Lowered rhs = lower(definition.rhs, scope, typeScope);
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
            // GHC JoinArity counts type binders; the runtime contract counts values in that raw prefix.
            int join = 0; Expr prefix = definition.rhs; Type result = variable.type;
            for (int i = 0; i < binder.info.join; i++) {
                if (prefix.tag != 4) throw error(definition.rhs, "join arity exceeds raw lambda prefix");
                Binder parameter = (Binder) prefix.fields.getFirst();
                if (parameter.typeVariable) {
                    if (!result.forall()) throw error(definition.rhs, "join type prefix has no forall");
                    if (!liftedKind(result.arguments.getFirst())) throw error(definition.rhs, "unsupported join forall kind");
                    result = replace(result.arguments.get(1), TYPE_VARIABLE + result.name.substring(FORALL.length()), Type.scalar(ABSTRACT_LIFTED + "erased"));
                } else {
                    if (!result.function()) throw error(definition.rhs, "join prefix has no function type");
                    result = result.result(); join++;
                }
                prefix = (Expr) prefix.fields.get(1);
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
        return map("id", variable.id, "name", binder.name, "lifted", lifted(variable.type, location), "coercion", false,
                "rep", rep(variable.type, evaluated || !lifted(variable.type, location), location), "info", information);
    }
    private Variable local(Binder binder) { return new Variable("\u0000hi-local:" + prefix() + localOrdinal++, binder.type); }
    private Lowered lower(Expr e, Map<String,Variable> scope, Map<String,Type> typeScope) {
        return switch (e.tag) {
            case 0 -> {
                String name = (String) e.fields.getFirst();
                var variable = scope.get(name);
                if (variable == null) throw error(e, "unbound retained Core local " + name);
                yield expression(resolve(variable.type, typeScope, e, true), e, "var", variable.id);
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
                if (type != null) type = resolve(type, Map.of(), e, true);
                if (constructor != null) yield expression(type, e, "con", id, (long) constructor.fields.size());
                if (type == null) throw error(e, "missing native Core dependency type " + id);
                yield expression(type, e, "var", id);
            }
            case 9 -> {
                var literal = (Literal) e.fields.getFirst();
                yield expression(literal.type, e, "lit", literal.kind, literal.value);
            }
            case 4 -> {
                var inner = new HashMap<>(scope); var innerTypes = new HashMap<>(typeScope);
                var parameters = new ArrayList<Map<String,Object>>(); var types = new ArrayList<Type>();
                var quantifiers = new ArrayList<Binder>(); var abstracts = new ArrayList<Type>();
                Expr body = e;
                while (body.tag == 4) {
                    Binder binder = (Binder) body.fields.getFirst();
                    if (binder.typeVariable) {
                        if (!parameters.isEmpty()) throw error(e, "unsupported non-prenex type lambda");
                        if (!liftedKind(binder.type)) throw error(e, "unsupported type binder kind " + binder.type);
                        Type abstractType = Type.scalar(ABSTRACT_LIFTED + prefix() + localOrdinal++);
                        innerTypes.put(binder.name, abstractType); quantifiers.add(binder); abstracts.add(abstractType);
                    } else {
                        Type type = resolve(binder.type, innerTypes, e, false);
                        Variable variable = new Variable(local(binder).id, type);
                        inner.put(binder.name, variable);
                        parameters.add(binder(binder, variable, false)); types.add(type);
                    }
                    body = (Expr) body.fields.get(1);
                }
                Lowered result = lower(body, inner, innerTypes);
                Type type = result.type;
                for (int i = types.size() - 1; i >= 0; i--) type = Type.fun(types.get(i), type);
                for (int i = quantifiers.size() - 1; i >= 0; i--) {
                    Binder quantifier = quantifiers.get(i);
                    String fresh = abstracts.get(i).name;
                    type = replace(type, fresh, Type.scalar(TYPE_VARIABLE + fresh));
                    type = new Type(FORALL + fresh, List.of(quantifier.type, type));
                }
                // Type-only lambdas erase to their body, just as the Core exporter does.
                if (parameters.isEmpty()) yield new Lowered(result.expression, type);
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
                Lowered head = lower(function, scope, typeScope); Type result = head.type;
                var arguments = new ArrayList<List<Object>>(args.size()); var lifted = new ArrayList<Boolean>(args.size());
                for (var argument : args) {
                    if (argument.tag == 1) {
                        if (!arguments.isEmpty() || !result.forall()) throw error(e, "unsupported type application without a prenex forall");
                        if (!liftedKind(result.arguments.getFirst())) throw error(e, "unsupported forall kind");
                        Type supplied = resolve((Type) argument.fields.getFirst(), typeScope, e, false);
                        if (!lifted(supplied, e)) throw error(e, "unsupported unlifted type argument");
                        result = replace(result.arguments.get(1), TYPE_VARIABLE + result.name.substring(FORALL.length()), supplied);
                        continue;
                    }
                    if (!result.function()) throw error(e, "unsupported application of a non-function scalar type");
                    Lowered value = lower(argument, scope, typeScope);
                    arguments.add(value.expression); lifted.add(lifted(value.type, e)); result = result.result();
                }
                if (arguments.isEmpty()) {
                    // A type-only application preserves the original function value and sharing.
                    var erased = new ArrayList<>(head.expression); var metadata = new LinkedHashMap<>(metadata(head.expression));
                    var original = (Map<?,?>) metadata.get("rep");
                    metadata.put("rep", rep(result, Boolean.TRUE.equals(original.get("evaluated")), e));
                    erased.set(erased.size() - 1, metadata);
                    yield new Lowered(erased, result);
                }
                // Retained interfaces do not carry Core's speculation predicates.
                yield expression(result, e, "app", head.expression, arguments, lifted, false, false);
            }
            case 6 -> {
                Lowered scrutinee = lower((Expr) e.fields.getFirst(), scope, typeScope);
                if (scrutinee.type.function()) throw error(e, "unsupported function case scrutinee");
                Binder binder = new Binder((String) e.fields.get(1), scrutinee.type, new Info(0, null, null), e.offset, false);
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
                        if (constructor == null || !constructor.result.name.equals(scrutinee.type.name) ||
                                constructor.parameters.size() != scrutinee.type.arguments.size()) throw error(e, "unsupported data alternative " + id);
                        var fieldScope = new HashMap<String,Type>();
                        for (int i = 0; i < constructor.parameters.size(); i++) {
                            Type supplied = scrutinee.type.arguments.get(i);
                            if (!lifted(supplied, e)) throw error(e, "unsupported unlifted data argument");
                            fieldScope.put(constructor.parameters.get(i).name, supplied);
                        }
                        if (alt.binders.size() != constructor.fields.size()) throw error(e, "constructor case field count mismatch " + id);
                        discriminator = id;
                        for (int i = 0; i < alt.binders.size(); i++) {
                            Binder field = new Binder(alt.binders.get(i), resolve(constructor.fields.get(i), fieldScope, e, false), new Info(0, null, null), alt.rhs.offset, false);
                            Variable fieldVariable = local(field); branch.put(field.name, fieldVariable);
                            ids.add(fieldVariable.id); parameters.add(binder(field, fieldVariable, !lifted(field.type, e)));
                        }
                    } else if (!alt.binders.isEmpty()) throw error(e, "unexpected scalar alternative binders");
                    Lowered rhs = lower(alt.rhs, branch, typeScope); if (result == null) result = rhs.type;
                    if (alt.tag == 2) {
                        Literal literal = (Literal) alt.discriminator;
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
                    Variable variable = new Variable(local(definition.binder).id, resolve(definition.binder.type, typeScope, e, true)); variables.add(variable); inner.put(definition.binder.name, variable);
                }
                var bindings = new ArrayList<Map<String,Object>>();
                for (int i = 0; i < group.definitions.size(); i++) bindings.add(binding(group.definitions.get(i), variables.get(i), group.recursive ? inner : scope, typeScope));
                Lowered body = lower((Expr) e.fields.get(1), inner, typeScope);
                yield expression(body.type, e, "let", group.recursive, bindings, body.expression);
            }
            default -> throw error(e, "unsupported executable expression tag " + e.tag);
        };
    }
    private Lowered expression(Type type, Expr origin, Object... fields) {
        var expression = values(fields);
        boolean evaluated = fields[0].equals("lam") || fields[0].equals("lit") || fields[0].equals("var") && !lifted(type, origin);
        expression.add(map("rep", rep(type, evaluated, origin)));
        return new Lowered(expression, type);
    }
    private static boolean liftedKind(Type kind) {
        Type lifted = Type.scalar("ghc-internal:GHC.Internal.Types.Lifted");
        return kind.equals(Type.scalar("ghc-internal:GHC.Internal.Types.Type")) ||
                kind.equals(new Type("ghc-internal:GHC.Internal.Prim.TYPE", List.of(Type.scalar("ghc-internal:GHC.Internal.Types.LiftedRep")))) ||
                kind.equals(new Type("ghc-internal:GHC.Internal.Prim.TYPE", List.of(new Type("ghc-internal:GHC.Internal.Types.BoxedRep", List.of(lifted)))));
    }
    private Type resolve(Type type, Map<String,Type> scope, Expr location, boolean prenex) {
        if (type.name.startsWith(TYPE_VARIABLE)) {
            var resolved = scope.get(type.name.substring(TYPE_VARIABLE.length()));
            if (resolved == null) throw error(location, "unbound type variable " + type.name);
            return resolved;
        }
        if (type.forall()) {
            if (!prenex) throw error(location, "unsupported higher-rank type");
            if (!liftedKind(type.arguments.getFirst())) throw error(location, "unsupported forall kind " + type.arguments.getFirst());
            var inner = new HashMap<>(scope); String name = type.name.substring(FORALL.length());
            String fresh = ABSTRACT_LIFTED + prefix() + localOrdinal++;
            inner.put(name, Type.scalar(TYPE_VARIABLE + fresh));
            return new Type(FORALL + fresh, List.of(type.arguments.getFirst(), resolve(type.arguments.get(1), inner, location, true)));
        }
        var arguments = new ArrayList<Type>(type.arguments.size());
        for (Type argument : type.arguments) arguments.add(resolve(argument, scope, location, false));
        return new Type(type.name, List.copyOf(arguments));
    }
    private static Type replace(Type type, String name, Type supplied) {
        if (type.name.equals(name)) return supplied;
        // Reconstructed quantifiers and supplied abstract variables use fresh lexical identities; respect binder shadowing.
        if (type.forall() && name.equals(TYPE_VARIABLE + type.name.substring(FORALL.length()))) return type;
        var arguments = new ArrayList<Type>(type.arguments.size());
        for (Type argument : type.arguments) arguments.add(replace(argument, name, supplied));
        return new Type(type.name, List.copyOf(arguments));
    }
    private Type runtimeType(Type type, Expr location) {
        while (type.forall()) {
            if (!liftedKind(type.arguments.getFirst())) throw error(location, "unsupported forall kind " + type.arguments.getFirst());
            type = replace(type.arguments.get(1), TYPE_VARIABLE + type.name.substring(FORALL.length()), Type.scalar(ABSTRACT_LIFTED + "erased"));
        }
        return resolve(type, Map.of(), location, false);
    }
    private static boolean scalar(Type type) {
        return type.arguments.isEmpty() && switch (type.name) {
            case "IntRep", "Int8Rep", "Int16Rep", "Int32Rep", "Int64Rep", "WordRep", "Word8Rep", "Word16Rep", "Word32Rep", "Word64Rep" -> true;
            default -> false;
        };
    }
    private boolean data(Type type) {
        if (type.name.indexOf(':') < 0) return false;
        var module = owner(type.name);
        var parameters = module == null ? null : module.dataTypes.get(type.name);
        if (parameters == null || parameters.size() != type.arguments.size()) return false;
        return type.arguments.stream().allMatch(argument -> argument.function() || argument.abstractLifted() || data(argument));
    }
    private boolean lifted(Type type, Expr location) {
        type = runtimeType(type, location);
        return type.function() || type.abstractLifted() || data(type);
    }
    private Map<String,Object> rep(Type type, boolean evaluated, Expr location) {
        type = runtimeType(type, location);
        if (type.abstractLifted()) return map("kind", "object", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", evaluated);
        if (type.function()) return map("kind", "closure", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", evaluated);
        if (type.equals(Type.scalar("AddrRep"))) return map("kind", "address", "primReps", List.of("AddrRep"), "evaluated", evaluated);
        if (type.equals(Type.scalar("FloatRep")) || type.equals(Type.scalar("DoubleRep")))
            return map("kind", type.name.equals("FloatRep") ? "float" : "double", "primReps", List.of(type.name), "evaluated", evaluated);
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
