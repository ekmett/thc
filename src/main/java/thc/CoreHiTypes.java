// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.math.BigInteger;
import java.util.*;
import java.util.function.Function;

/** Kinded interface types and coercions. Variables have lexical identity; substitution
 * respects shadowing, and pi application uses the same substitution as declarations and axioms. */
final class CoreHiTypes {
    sealed interface Ty permits Var, Con, App, Fun, ForAll, Tuple, Lit, Cast, CoTy {}
    record Variable(Object identity, String spelling, Ty kind, boolean coercion) {
        Variable(String spelling, Ty kind, boolean coercion) { this(new Object(), spelling, kind, coercion); }
    }
    record Var(Variable variable) implements Ty {}
    record Arg(Ty type, int visibility) {}
    record Sort(int tag, int arity, int tupleSort) {}
    record Con(CoreHiReader.ExternalName name, boolean promoted, Sort sort, List<Arg> arguments) implements Ty {
        Con { arguments = List.copyOf(arguments); }
    }
    record App(Ty head, List<Arg> arguments) implements Ty { App { arguments = List.copyOf(arguments); } }
    record Fun(int flag, Ty multiplicity, Ty argument, Ty result) implements Ty {}
    record ForAll(Variable variable, int visibility, Ty body) implements Ty {}
    record Tuple(int sort, boolean promoted, List<Arg> arguments) implements Ty { Tuple { arguments = List.copyOf(arguments); } }
    record Lit(int sort, Object value) implements Ty {}
    record Cast(Ty type, Co coercion) implements Ty {}
    record CoTy(Co coercion) implements Ty {}
    record Co(String tag, List<Object> fields) { Co { fields = List.copyOf(fields); } }
    record Parameter(Variable variable, List<Integer> visibility) { Parameter { visibility = List.copyOf(visibility); } }
    record Branch(List<Variable> variables, List<Integer> roles, List<Ty> lhs, Ty rhs) {
        Branch { variables = List.copyOf(variables); roles = List.copyOf(roles); lhs = List.copyOf(lhs); }
    }
    record Axiom(CoreHiReader.ExternalName name, CoreHiReader.ExternalName tycon, int role, List<Branch> branches) {
        Axiom { branches = List.copyOf(branches); }
    }
    record Declaration(CoreHiReader.ExternalName name, List<Parameter> parameters, Ty kind, Ty resultKind,
                       List<Integer> roles, String form, Ty rhs, Axiom axiom) {
        Declaration { parameters = List.copyOf(parameters); roles = List.copyOf(roles); }
    }
    private final Function<CoreHiReader.ExternalName, Declaration> declarations;
    private final Function<CoreHiReader.ExternalName, Axiom> axioms;
    CoreHiTypes(Function<CoreHiReader.ExternalName, Declaration> declarations, Function<CoreHiReader.ExternalName, Axiom> axioms) {
        this.declarations = declarations; this.axioms = axioms;
    }

    private static List<?> list(Object value) { return (List<?>) value; }
    private static int number(Object value) { return ((Number) value).intValue(); }
    static CoreHiReader.ExternalName name(Object value) {
        if (value instanceof CoreHiReader.ExternalName name) return name;
        List<?> xs = list(value);
        return new CoreHiReader.ExternalName(new CoreHiReader.ModuleId((String) xs.get(0), (String) xs.get(1)),
                number(xs.get(2)), (String) xs.get(3), (String) xs.get(4));
    }
    private static Sort sort(Object value) {
        List<?> xs = list(value); int tag = number(xs.getFirst());
        return new Sort(tag, xs.size() > 1 ? number(xs.get(1)) : 0, xs.size() > 2 ? number(xs.get(2)) : 0);
    }
    private List<Arg> arguments(Object value, Map<String, Variable> scope) {
        var result = new ArrayList<Arg>();
        for (Object raw : list(value)) { List<?> pair = list(raw); result.add(new Arg(read(pair.get(1), scope), number(pair.getFirst()))); }
        return List.copyOf(result);
    }
    Variable binder(Object value, Map<String, Variable> scope) {
        List<?> xs = list(value);
        return new Variable((String) xs.get(0), read(xs.get(2), scope), (Boolean) xs.get(1));
    }
    Ty read(Object value, Map<String, Variable> scope) {
        if (value instanceof Ty type) return type;
        List<?> xs = list(value); String tag = (String) xs.getFirst();
        return switch (tag) {
            case "var" -> {
                Variable variable = scope.get((String) xs.get(1));
                if (variable == null) throw new IllegalArgumentException("Unbound interface type variable " + xs.get(1));
                yield new Var(variable);
            }
            case "con" -> smartCon(new Con(name(xs.get(1)), (Boolean) xs.get(2), sort(xs.get(3)), arguments(xs.get(4), scope)));
            case "app" -> apply(read(xs.get(1), scope), arguments(xs.get(2), scope));
            case "fun" -> new Fun(number(xs.get(1)), read(xs.get(2), scope), read(xs.get(3), scope), read(xs.get(4), scope));
            case "forall" -> {
                Variable variable = binder(xs.get(1), scope); var nested = new HashMap<>(scope); nested.put(variable.spelling, variable);
                yield new ForAll(variable, number(xs.get(2)), read(xs.get(3), nested));
            }
            case "tuple" -> new Tuple(number(xs.get(1)), (Boolean) xs.get(2), arguments(xs.get(3), scope));
            case "lit" -> {
                int sort = number(xs.get(1));
                Object literal = sort == 1 ? new BigInteger((String) xs.get(2)) :
                    sort == 2 ? list(xs.get(2)).stream().map(CoreHiTypes::number).toList() : xs.get(2);
                yield new Lit(sort, literal);
            }
            case "cast" -> new Cast(read(xs.get(1), scope), readCo(xs.get(2), scope));
            case "coercion" -> new CoTy(readCo(xs.get(1), scope));
            default -> throw new IllegalArgumentException("Unknown native type AST node " + tag);
        };
    }
    private static Co co(String tag, Object... fields) { return new Co(tag, Arrays.asList(fields)); }
    Co readCo(Object value, Map<String, Variable> scope) {
        if (value instanceof Co coercion) return coercion;
        List<?> xs = list(value); String tag = (String) xs.getFirst();
        return switch (tag) {
            case "refl" -> co(tag, read(xs.get(1), scope));
            case "grefl" -> co(tag, number(xs.get(1)), read(xs.get(2), scope), xs.get(3) == null ? Optional.empty() : Optional.of(readCo(xs.get(3), scope)));
            case "funco" -> co(tag, number(xs.get(1)), readCo(xs.get(2), scope), readCo(xs.get(3), scope), readCo(xs.get(4), scope));
            case "conco" -> co(tag, number(xs.get(1)), name(xs.get(2)), (Boolean) xs.get(3), sort(xs.get(4)), coercions(xs.get(5), scope));
            case "appco", "trans", "inst" -> co(tag, readCo(xs.get(1), scope), readCo(xs.get(2), scope));
            case "forallco" -> {
                Variable variable = binder(xs.get(1), scope); var nested = new HashMap<>(scope); nested.put(variable.spelling, variable);
                yield co(tag, variable, number(xs.get(2)), number(xs.get(3)), readCo(xs.get(4), scope), readCo(xs.get(5), nested));
            }
            case "varco" -> {
                Variable variable = scope.get((String) xs.get(1));
                if (variable == null || !variable.coercion) throw new IllegalArgumentException("Unbound interface coercion variable " + xs.get(1));
                yield co(tag, variable);
            }
            case "univ" -> co(tag, List.copyOf(list(xs.get(1))), number(xs.get(2)), read(xs.get(3), scope), read(xs.get(4), scope), coercions(xs.get(5), scope));
            case "sym", "kind", "sub" -> co(tag, readCo(xs.get(1), scope));
            case "sel" -> co(tag, List.copyOf(list(xs.get(1))), readCo(xs.get(2), scope));
            case "lr" -> co(tag, number(xs.get(1)), readCo(xs.get(2), scope));
            case "axiom" -> {
                List<?> rule = list(xs.get(1)); var converted = new ArrayList<Object>(rule);
                if (number(rule.getFirst()) != 0) converted.set(1, name(rule.get(1)));
                yield co(tag, List.copyOf(converted), coercions(xs.get(2), scope));
            }
            default -> throw new IllegalArgumentException("Unknown native coercion AST node " + tag);
        };
    }
    private List<Co> coercions(Object value, Map<String, Variable> scope) {
        return list(value).stream().map(raw -> readCo(raw, scope)).toList();
    }
    private static Ty smartCon(Con con){
        if(!con.promoted&&con.name.module().equals(new CoreHiReader.ModuleId("ghc-internal","GHC.Internal.Prim"))&&con.name.namespace()==3){
            int flag=switch(con.name.occurrence()){case "FUN"->0;case "-=>"->1;case "=>"->2;case "==>"->3;default->-1;};
            int n=flag==0?5:4;if(flag>=0&&con.arguments.size()==n)return new Fun(flag,flag==0?con.arguments.getFirst().type:dataCon("Many"),con.arguments.get(n-2).type,con.arguments.get(n-1).type);
        }
        return con;
    }
    static Ty apply(Ty head, List<Arg> arguments) {
        if (arguments.isEmpty()) return head;
        if (head instanceof Con con) { var xs = new ArrayList<>(con.arguments); xs.addAll(arguments); return smartCon(new Con(con.name, con.promoted, con.sort, xs)); }
        if (head instanceof App app) { var xs = new ArrayList<>(app.arguments); xs.addAll(arguments); return new App(app.head, xs); }
        return new App(head, arguments);
    }
    Ty piApply(Ty head, List<Ty> arguments) {
        var types = new HashMap<Variable, Ty>(); var coercions = new HashMap<Variable, Co>();
        for (Ty argument : arguments) {
            if (head instanceof ForAll forall) {
                if (forall.variable.coercion) {
                    if (!(argument instanceof CoTy co)) throw new IllegalArgumentException("Coercion pi binder requires a coercion argument");
                    coercions.put(forall.variable, co.coercion);
                } else types.put(forall.variable, argument);
                head = forall.body;
            } else if (head instanceof Fun fun) head = fun.result;
            else {
                Ty expanded = view(substitute(head, types, coercions), false);
                types.clear(); coercions.clear();
                if (expanded instanceof ForAll || expanded instanceof Fun) {
                    head = piApply(expanded, List.of(argument));
                } else throw new IllegalArgumentException("Cannot pi-apply non-pi type " + expanded);
            }
        }
        return substitute(head, types, coercions);
    }
    private static Set<Variable> free(Ty type) { var result = new HashSet<Variable>(); collect(type, result); return result; }
    private static void collect(Ty type, Set<Variable> result) {
        if (type instanceof Var var) { result.add(var.variable); collect(var.variable.kind, result); }
        else if (type instanceof Con con) con.arguments.forEach(a -> collect(a.type, result));
        else if (type instanceof App app) { collect(app.head, result); app.arguments.forEach(a -> collect(a.type, result)); }
        else if (type instanceof Fun fun) { collect(fun.multiplicity, result); collect(fun.argument, result); collect(fun.result, result); }
        else if (type instanceof ForAll forall) { var body = free(forall.body); body.remove(forall.variable); result.addAll(body); collect(forall.variable.kind, result); }
        else if (type instanceof Tuple tuple) tuple.arguments.forEach(a -> collect(a.type, result));
        else if (type instanceof Cast cast) { collect(cast.type, result); collectCo(cast.coercion, result); }
        else if (type instanceof CoTy co) collectCo(co.coercion, result);
    }
    private static void collectCo(Co coercion, Set<Variable> result) {
        if (coercion.tag.equals("varco")) { Variable var = (Variable) coercion.fields.getFirst(); result.add(var); collect(var.kind, result); return; }
        if (coercion.tag.equals("forallco")) {
            Variable var = (Variable) coercion.fields.getFirst(); var body = new HashSet<Variable>(); collectObject(coercion.fields.get(4), body); body.remove(var);
            result.addAll(body); collect(var.kind, result); collectObject(coercion.fields.get(3), result); return;
        }
        coercion.fields.forEach(f -> collectObject(f, result));
    }
    private static void collectObject(Object value, Set<Variable> result) {
        if (value instanceof Ty ty) collect(ty, result); else if (value instanceof Co co) collectCo(co, result);
        else if (value instanceof List<?> xs) xs.forEach(x -> collectObject(x, result)); else if (value instanceof Optional<?> maybe) maybe.ifPresent(x -> collectObject(x, result));
    }
    static Ty substitute(Ty type, Map<Variable, Ty> substitution) { return substitute(type, substitution, Map.of()); }
    static Ty substitute(Ty type, Map<Variable, Ty> types, Map<Variable, Co> coercions) {
        if (types.isEmpty() && coercions.isEmpty()) return type;
        if (type instanceof Var var) return types.getOrDefault(var.variable, type);
        if (type instanceof Con con) return smartCon(new Con(con.name, con.promoted, con.sort, substArgs(con.arguments, types, coercions)));
        if (type instanceof App app) return apply(substitute(app.head, types, coercions), substArgs(app.arguments, types, coercions));
        if (type instanceof Fun fun) return new Fun(fun.flag, substitute(fun.multiplicity, types, coercions), substitute(fun.argument, types, coercions), substitute(fun.result, types, coercions));
        if (type instanceof ForAll forall) {
            var binding = substituteBinder(forall.variable, types, coercions);
            return new ForAll(binding.variable, forall.visibility, substitute(forall.body, binding.types, binding.coercions));
        }
        if (type instanceof Tuple tuple) return new Tuple(tuple.sort, tuple.promoted, substArgs(tuple.arguments, types, coercions));
        if (type instanceof Cast cast) return new Cast(substitute(cast.type, types, coercions), substitute(cast.coercion, types, coercions));
        if (type instanceof CoTy co) return new CoTy(substitute(co.coercion, types, coercions));
        return type;
    }
    private static List<Arg> substArgs(List<Arg> args, Map<Variable, Ty> types, Map<Variable, Co> coercions) {
        return args.stream().map(a -> new Arg(substitute(a.type, types, coercions), a.visibility)).toList();
    }
    private record Binding(Variable variable, Map<Variable, Ty> types, Map<Variable, Co> coercions) {}
    private static Binding substituteBinder(Variable old, Map<Variable, Ty> types, Map<Variable, Co> coercions) {
        var ts = new HashMap<>(types); ts.remove(old); var cs = new HashMap<>(coercions); cs.remove(old);
        Ty kind = substitute(old.kind, types, coercions); var capture = new HashSet<Variable>();
        ts.values().forEach(t -> collect(t, capture)); cs.values().forEach(c -> collectCo(c, capture));
        if (kind == old.kind && !capture.contains(old)) return new Binding(old, ts, cs);
        Variable fresh = new Variable(old.spelling, kind, old.coercion);
        if (old.coercion) cs.put(old, co("varco", fresh)); else ts.put(old, new Var(fresh));
        return new Binding(fresh, ts, cs);
    }
    static Co substitute(Co coercion, Map<Variable, Ty> types, Map<Variable, Co> coercions) {
        if (types.isEmpty() && coercions.isEmpty()) return coercion;
        if (coercion.tag.equals("varco")) return coercions.getOrDefault((Variable) coercion.fields.getFirst(), coercion);
        if (coercion.tag.equals("forallco")) {
            var binding = substituteBinder((Variable) coercion.fields.getFirst(), types, coercions);
            return co("forallco", binding.variable, coercion.fields.get(1), coercion.fields.get(2),
                    substitute((Co) coercion.fields.get(3), types, coercions), substitute((Co) coercion.fields.get(4), binding.types, binding.coercions));
        }
        return new Co(coercion.tag, coercion.fields.stream().map(f -> substituteObject(f, types, coercions)).toList());
    }
    private static Object substituteObject(Object value, Map<Variable, Ty> types, Map<Variable, Co> coercions) {
        if (value instanceof Ty ty) return substitute(ty, types, coercions); if (value instanceof Co co) return substitute(co, types, coercions);
        if (value instanceof Optional<?> maybe) return maybe.map(x -> substituteObject(x, types, coercions));
        if (value instanceof List<?> xs) return xs.stream().map(x -> substituteObject(x, types, coercions)).toList(); return value;
    }

    Declaration declaration(Map<?,?> raw) {
        var scope = new HashMap<String,Variable>(); var parameters = new ArrayList<Parameter>();
        for (Object entry : list(raw.get("binders"))) {
            List<?> xs = list(entry); Variable variable = binder(xs.getFirst(), scope); scope.put(variable.spelling, variable);
            parameters.add(new Parameter(variable, list(xs.get(1)).stream().map(CoreHiTypes::number).toList()));
        }
        var name = name(raw.get("name"));
        Ty kind = read(raw.get("kind"), Map.of()), resultKind = read(raw.get("resultKind"), scope);
        Ty rhs = raw.get("rhs") == null ? null : read(raw.get("rhs"), scope);
        var roles = list(raw.get("roles")).stream().map(CoreHiTypes::number).toList();
        Axiom axiom = raw.get("axiom") == null ? null : axiom((Map<?,?>) raw.get("axiom"), name);
        return new Declaration(name, parameters, kind, resultKind, roles, (String) raw.get("form"), rhs, axiom);
    }
    Axiom axiom(Map<?,?> raw, CoreHiReader.ExternalName tycon) {
        var branches = new ArrayList<Branch>();
        for (Object value : list(raw.get("branches"))) {
            Map<?,?> branch = (Map<?,?>) value; var scope = new HashMap<String,Variable>(); var variables = new ArrayList<Variable>();
            for (Object b : list(branch.get("binders"))) { Variable v = binder(b, scope); scope.put(v.spelling, v); variables.add(v); }
            var roles = list(branch.get("roles")).stream().map(CoreHiTypes::number).toList();
            var lhs = list(branch.get("lhs")).stream().map(t -> read(t, scope)).toList();
            branches.add(new Branch(variables, roles, lhs, read(branch.get("rhs"), scope)));
        }
        return new Axiom(name(raw.get("name")), tycon, number(raw.get("role")), branches);
    }
    private static CoreHiReader.ExternalName builtin(String module, int namespace, String occurrence) {
        return new CoreHiReader.ExternalName(new CoreHiReader.ModuleId("ghc-internal", module), namespace, null, occurrence);
    }
    static Ty con(CoreHiReader.ExternalName name, boolean promoted, Ty... arguments) {
        return smartCon(new Con(name, promoted, new Sort(0,0,0), Arrays.stream(arguments).map(t -> new Arg(t,0)).toList()));
    }
    private static Ty typeCon(String module, String occurrence, Ty... arguments) { return con(builtin(module,3,occurrence), false, arguments); }
    private static Ty dataCon(String occurrence, Ty... arguments) { return con(builtin("GHC.Internal.Types",1,occurrence), true, arguments); }
    /** GHC occCheckExpand: expand synonyms where needed to remove a forbidden
     * kind occurrence, preserving lexical scopes throughout types and proofs. */
    private Ty avoid(Ty input,Set<Variable> variables) {
        if (Collections.disjoint(free(input),variables)) return input;
        if (input instanceof Var) return null;
        if (input instanceof Con con) {
            var args = new ArrayList<Arg>();
            for (Arg arg : con.arguments) {
                Ty expanded = avoid(arg.type,variables);
                if (expanded == null) {
                    Ty viewed = synonymView(con);
                    return viewed.equals(con) ? null : avoid(viewed,variables);
                }
                args.add(new Arg(expanded,arg.visibility));
            }
            return smartCon(new Con(con.name,con.promoted,con.sort,args));
        }
        if (input instanceof Tuple tuple) return avoid(tupleConstructor(tuple),variables);
        if (input instanceof App app) {
            Ty head = avoid(app.head,variables);
            if (head == null) return null;
            var args = new ArrayList<Arg>();
            for (Arg arg : app.arguments) {
                Ty type = avoid(arg.type,variables);
                if (type == null) return null;
                args.add(new Arg(type,arg.visibility));
            }
            return apply(head,args);
        }
        if (input instanceof Fun fun) {
            Ty m = avoid(fun.multiplicity,variables);
            Ty a = avoid(fun.argument,variables);
            Ty r = avoid(fun.result,variables);
            return m == null || a == null || r == null ? null : new Fun(fun.flag,m,a,r);
        }
        if (input instanceof ForAll forall) {
            Ty kind = avoid(forall.variable.kind,variables);
            if (kind == null) return null;
            Variable fresh = new Variable(forall.variable.spelling,kind,forall.variable.coercion);
            var remaining = new HashSet<>(variables);
            remaining.remove(forall.variable);
            Ty body = forall.variable.coercion ?
                substitute(forall.body,Map.of(),Map.of(forall.variable,co("varco",fresh))) :
                substitute(forall.body,Map.of(forall.variable,new Var(fresh)));
            body = avoid(body,remaining);
            return body == null ? null : new ForAll(fresh,forall.visibility,body);
        }
        if (input instanceof Cast cast) {
            Ty type = avoid(cast.type,variables);
            Co coercion = avoidCo(cast.coercion,variables);
            return type == null || coercion == null ? null : new Cast(type,coercion);
        }
        if (input instanceof CoTy proof) {
            Co coercion = avoidCo(proof.coercion,variables);
            return coercion == null ? null : new CoTy(coercion);
        }
        return input;
    }
    private Co avoidCo(Co input,Set<Variable> variables) {
        if (input.tag.equals("varco")) {
            Variable v = (Variable) input.fields.getFirst();
            return variables.contains(v) || !Collections.disjoint(free(v.kind),variables) ? null : input;
        }
        if (input.tag.equals("forallco")) {
            Variable v = (Variable) input.fields.getFirst();
            Ty kind = avoid(v.kind,variables);
            Co kindCoercion = avoidCo((Co) input.fields.get(3),variables);
            if (kind == null || kindCoercion == null) return null;
            Variable fresh = new Variable(v.spelling,kind,v.coercion);
            Co body = v.coercion ?
                substitute((Co) input.fields.get(4),Map.of(),Map.of(v,co("varco",fresh))) :
                substitute((Co) input.fields.get(4),Map.of(v,new Var(fresh)),Map.of());
            var remaining = new HashSet<>(variables);
            remaining.remove(v);
            body = avoidCo(body,remaining);
            return body == null ? null : co(input.tag,fresh,input.fields.get(1),input.fields.get(2),kindCoercion,body);
        }
        var fields = new ArrayList<Object>();
        for (Object field : input.fields) {
            Object expanded = avoidObject(field,variables);
            if (expanded == null) return null;
            fields.add(expanded);
        }
        return new Co(input.tag,fields);
    }
    private Object avoidObject(Object value,Set<Variable> variables) {
        if (value instanceof Ty type) return avoid(type,variables);
        if (value instanceof Co coercion) return avoidCo(coercion,variables);
        if (value instanceof Optional<?> maybe) {
            if (maybe.isEmpty()) return maybe;
            Object expanded = avoidObject(maybe.get(),variables);
            return expanded == null ? null : Optional.of(expanded);
        }
        if (value instanceof List<?> xs) {
            var result = new ArrayList<Object>();
            for (Object x : xs) {
                Object expanded = avoidObject(x,variables);
                if (expanded == null) return null;
                result.add(expanded);
            }
            return List.copyOf(result);
        }
        return value;
    }
    Ty kind(Ty type) {
        if (type instanceof Var var) return var.variable.kind;
        if (type instanceof Con con) {
            Declaration declaration = declarations.apply(con.name);
            if (declaration == null) throw new IllegalArgumentException("Missing native type declaration " + CoreHiNames.id(con.name));
            return piApply(declaration.kind, con.arguments.stream().map(Arg::type).toList());
        }
        if (type instanceof App app) return piApply(kind(app.head), app.arguments.stream().map(Arg::type).toList());
        if (type instanceof Fun fun) return (fun.flag & 1) == 0 ? typeCon("GHC.Internal.Types", "Type") : typeCon("GHC.Internal.Types", "Constraint");
        if (type instanceof ForAll) {
            var variables = new ArrayList<Variable>(); Ty body = type;
            while (body instanceof ForAll forall) { variables.add(forall.variable); body = forall.body; }
            Ty result = avoid( kind(body),new HashSet<>(variables));
            if (result==null)
                throw new IllegalArgumentException("Quantified variable escapes its type kind");
            return variables.stream().anyMatch(Variable::coercion)
                    ? typeCon("GHC.Internal.Types", constraintKind(result) ? "Constraint" : "Type") : result;
        }
        if (type instanceof Cast cast) return endpoints(cast.coercion).get(1);
        if (type instanceof CoTy co) return coercionType(co.coercion);
        if (type instanceof Lit literal) return switch (literal.sort) {
            case 1 -> typeCon("GHC.Internal.Bignum.Natural", "Natural");
            case 2 -> typeCon("GHC.Internal.Types", "Symbol");
            case 3 -> typeCon("GHC.Internal.Types", "Char");
            default -> throw new IllegalArgumentException("Invalid type literal sort " + literal.sort);
        };
        Tuple tuple = (Tuple) type;
        if (tuple.promoted || tuple.sort != 1) return kind(tupleConstructor(tuple));
        int n = tuple.arguments.size()/2;
        Ty reps = promotedList(typeCon("GHC.Internal.Types", "RuntimeRep"), tuple.arguments.subList(0,n).stream().map(Arg::type).toList());
        return typeCon("GHC.Internal.Prim", "TYPE", dataCon("TupleRep", reps));
    }
    private static Ty promotedList(Ty kind, List<Ty> values) {
        Ty result = con(builtin("GHC.Internal.Types",1,"[]"),true,kind);
        for (int i=values.size()-1;i>=0;i--) result = con(builtin("GHC.Internal.Types",1,":"),true,kind,values.get(i),result);
        return result;
    }
    private Ty tupleConstructor(Tuple tuple) {
        int count = tuple.arguments.size(), arity = tuple.sort == 1 && !tuple.promoted ? count/2 : count;
        CoreHiReader.ExternalName name = CoreHiNames.tupleName(tuple.sort, arity, tuple.promoted ? 1 : 3);
        var arguments = new ArrayList<Arg>();
        if (tuple.promoted) tuple.arguments.forEach(a -> arguments.add(new Arg(kind(a.type), 1)));
        arguments.addAll(tuple.arguments);
        return new Con(name, tuple.promoted, new Sort(1,arity,tuple.sort), arguments);
    }
    Declaration tupleDeclaration(CoreHiReader.ExternalName name) {
        var family=CoreHiNames.tupleFamily(name);if(family==null)return null;
        int n=family.arity(),sort=family.sort();
        Ty lifted=typeCon("GHC.Internal.Types","Type"),constraint=typeCon("GHC.Internal.Types","Constraint");
        Ty rr=typeCon("GHC.Internal.Types","RuntimeRep");
        var parameters=new ArrayList<Parameter>();var components=new ArrayList<Ty>();var reps=new ArrayList<Ty>();
        boolean promoted=name.namespace()==1;
        if(sort==1||promoted)for(int i=0;i<n;i++){
            Variable v=new Variable("k"+i,sort==1?rr:lifted,false);parameters.add(new Parameter(v,List.of(1,1)));reps.add(new Var(v));
        }
        for(int i=0;i<n;i++){
            Ty k=sort==1?typeCon("GHC.Internal.Prim","TYPE",reps.get(i)):promoted?reps.get(i):sort==2?constraint:lifted;
            Variable v=new Variable("a"+i,k,false);parameters.add(new Parameter(v,List.of(0)));components.add(new Var(v));
        }
        Ty result=sort==1?typeCon("GHC.Internal.Prim","TYPE",dataCon("TupleRep",promotedList(rr,reps))):sort==2?constraint:lifted;
        if(promoted)result=con(CoreHiNames.tupleName(sort,n,3),false,components.toArray(Ty[]::new));
        Ty kind=result;
        for(int i=parameters.size()-1;i>=0;i--){var p=parameters.get(i);kind=p.visibility.getFirst()==0?new Fun(functionFlag(p.variable.kind,kind),dataCon("Many"),p.variable.kind,kind):new ForAll(p.variable,p.visibility.get(1),kind);}
        var roles=new ArrayList<Integer>();for(int i=0;i<parameters.size();i++)roles.add(sort==2||sort==1&&i<n||promoted?1:2);
        return new Declaration(name,parameters,kind,result,roles,"data",null,null);
    }
    Declaration sumDeclaration(CoreHiReader.ExternalName name){
        var family=CoreHiNames.sumFamily(name);if(family==null||family.alternative()!=-1)return null;
        Declaration tuple=tupleDeclaration(CoreHiNames.tupleName(1,family.arity(),3));
        var reps=tuple.parameters.subList(0,family.arity()).stream().map(p->(Ty)new Var(p.variable)).toList();
        Ty result=typeCon("GHC.Internal.Prim","TYPE",dataCon("SumRep",promotedList(typeCon("GHC.Internal.Types","RuntimeRep"),reps)));Ty kind=result;
        for(int i=tuple.parameters.size()-1;i>=0;i--){var p=tuple.parameters.get(i);kind=p.visibility.getFirst()==0?new Fun(0,dataCon("Many"),p.variable.kind,kind):new ForAll(p.variable,p.visibility.get(1),kind);}
        return new Declaration(name,tuple.parameters,kind,result,tuple.roles,"data",null,null);
    }
    Axiom newtypeAxiom(Declaration declaration) {
        var parameters=new ArrayList<>(declaration.parameters);Ty rhs=declaration.rhs;
        while(!parameters.isEmpty()){
            Variable last=parameters.getLast().variable;List<Ty> split;
            try{split=splitApplication(rhs);}catch(IllegalArgumentException nonApplication){break;}
            if(!(split.get(1) instanceof Var var)||!var.variable.equals(last)||free(split.getFirst()).contains(last)||!equal(kind(split.getFirst()),kind(con(declaration.name,false,parameters.subList(0,parameters.size()-1).stream().map(p->(Ty)new Var(p.variable)).toArray(Ty[]::new)))))break;
            parameters.removeLast();rhs=split.getFirst();
        }
        var variables=parameters.stream().map(Parameter::variable).toList();var name=new CoreHiReader.ExternalName(declaration.name.module(),3,null,"N:"+declaration.name.occurrence());
        return new Axiom(name,declaration.name,2,List.of(new Branch(variables,declaration.roles.subList(0,variables.size()),variables.stream().map(v->(Ty)new Var(v)).toList(),rhs)));
    }
    private boolean constraintKind(Ty kind) {
        Ty expanded = view(kind, false);
        return expanded instanceof Con con && con.name.equals(builtin("GHC.Internal.Prim",3,"CONSTRAINT"));
    }
    private int functionFlag(Ty argument, Ty result) { return (constraintKind(kind(argument)) ? 2 : 0) | (constraintKind(kind(result)) ? 1 : 0); }
    Ty coercionType(Co coercion) {
        var ends = endpoints(coercion); int role = role(coercion);
        String equality = switch (role) { case 1 -> "~#"; case 2 -> "~R#"; case 3 -> "~P#"; default -> throw new IllegalArgumentException("Invalid coercion role " + role); };
        return con(builtin("GHC.Internal.Prim",3,equality),false,kind(ends.getFirst()),kind(ends.get(1)),ends.getFirst(),ends.get(1));
    }
    int role(Co coercion) {
        return switch (coercion.tag) {
            case "refl", "kind", "lr" -> 1;
            case "grefl", "funco", "conco" -> number(coercion.fields.getFirst());
            case "univ" -> number(coercion.fields.get(1));
            case "sub" -> 2;
            case "appco", "sym", "trans", "inst" -> role((Co) coercion.fields.getFirst());
            case "forallco" -> role((Co) coercion.fields.get(4));
            case "varco" -> equalityRole(((Variable) coercion.fields.getFirst()).kind);
            case "sel" -> {
                List<?> selector = list(coercion.fields.getFirst());
                int tag=number(selector.getFirst()),parent=role((Co)coercion.fields.get(1));yield tag==0?number(selector.get(2)):tag==1?1:tag==2&&parent==2?1:parent;
            }
            case "axiom" -> {
                List<?> rule = list(coercion.fields.getFirst());
                if (number(rule.getFirst()) == 0) yield CoreHiAxioms.role((String) rule.get(1));
                Axiom axiom = axioms.apply((CoreHiReader.ExternalName) rule.get(1));
                if (axiom == null) throw new IllegalArgumentException("Missing native coercion axiom " + CoreHiNames.id((CoreHiReader.ExternalName) rule.get(1)));
                yield axiom.role;
            }
            default -> throw new IllegalArgumentException("Unknown coercion " + coercion.tag);
        };
    }
    private int equalityRole(Ty type) {
        type = view(type,false);
        if (!(type instanceof Con con)) throw new IllegalArgumentException("Coercion variable has no equality type " + type);
        return switch (con.name.occurrence()) { case "~#" -> 1; case "~R#" -> 2; case "~P#" -> 3; default -> throw new IllegalArgumentException("Not an equality type " + type); };
    }
    List<Ty> endpoints(Co coercion) {
        List<Object> f = coercion.fields;
        return switch (coercion.tag) {
            case "refl" -> List.of((Ty)f.getFirst(), (Ty)f.getFirst());
            case "grefl" -> {
                Ty type = (Ty)f.get(1); Optional<?> kindCo = (Optional<?>)f.get(2);
                yield List.of(type, kindCo.isEmpty() ? type : new Cast(type,(Co)kindCo.get()));
            }
            case "funco" -> {
                var multiplicity = endpoints((Co)f.get(1)); var argument = endpoints((Co)f.get(2)); var result = endpoints((Co)f.get(3));
                yield List.of(new Fun(functionFlag(argument.getFirst(),result.getFirst()),multiplicity.getFirst(),argument.getFirst(),result.getFirst()),
                        new Fun(functionFlag(argument.get(1),result.get(1)),multiplicity.get(1),argument.get(1),result.get(1)));
            }
            case "conco" -> {
                var left = new ArrayList<Arg>();var right = new ArrayList<Arg>();
                var name = (CoreHiReader.ExternalName)f.get(1); Declaration declaration = declarations.apply(name);
                for (Object raw:list(f.get(4))) { var pair = endpoints((Co)raw);int i=left.size();int visibility=0;
                    if(declaration!=null&&i<declaration.parameters.size()){var vis=declaration.parameters.get(i).visibility;visibility=vis.getFirst()==0?0:vis.get(1);}
                    left.add(new Arg(pair.getFirst(),visibility));right.add(new Arg(pair.get(1),visibility)); }
                yield List.of(new Con(name,(Boolean)f.get(2),(Sort)f.get(3),left),new Con(name,(Boolean)f.get(2),(Sort)f.get(3),right));
            }
            case "appco" -> { var a=endpoints((Co)f.getFirst());var b=endpoints((Co)f.get(1));yield List.of(apply(a.getFirst(),List.of(new Arg(b.getFirst(),0))),apply(a.get(1),List.of(new Arg(b.get(1),0)))); }
            case "forallco" -> {
                Variable left=(Variable)f.getFirst();Co kindCo=(Co)f.get(3);Co body=(Co)f.get(4);var k=endpoints(kindCo);
                Variable right=new Variable(left.spelling,k.get(1),left.coercion);
                Co rightBody;
                if(left.coercion){
                    int r=equalityRole(left.kind);
                    Co before=co("sel",List.of(0,2,r),kindCo);
                    Co after=co("sym",co("sel",List.of(0,3,r),kindCo));
                    Co replacement=co("trans",before,co("trans",co("varco",right),after));
                    rightBody=substitute(body,Map.of(),Map.of(left,replacement));
                }else rightBody=substitute(body,Map.of(left,new Cast(new Var(right),co("sym",kindCo))),Map.of());
                yield List.of(new ForAll(left,number(f.get(1)),endpoints(body).getFirst()),new ForAll(right,number(f.get(2)),endpoints(rightBody).get(1)));
            }
            case "varco" -> {
                Ty type=view(((Variable)f.getFirst()).kind,false);
                if(!(type instanceof Con con)||con.arguments.size()<2)throw new IllegalArgumentException("Invalid coercion variable equality type " + type);
                yield List.of(con.arguments.get(con.arguments.size()-2).type,con.arguments.getLast().type);
            }
            case "univ" -> List.of((Ty)f.get(2),(Ty)f.get(3));
            case "sym" -> { var pair=endpoints((Co)f.getFirst());yield List.of(pair.get(1),pair.getFirst()); }
            case "trans" -> { var left=endpoints((Co)f.getFirst());var right=endpoints((Co)f.get(1));yield List.of(left.getFirst(),right.get(1)); }
            case "sel" -> { var pair=endpoints((Co)f.get(1));yield List.of(select(list(f.getFirst()),pair.getFirst()),select(list(f.getFirst()),pair.get(1))); }
            case "lr" -> { var pair=endpoints((Co)f.get(1));int side=number(f.getFirst());yield List.of(splitApplication(pair.getFirst()).get(side),splitApplication(pair.get(1)).get(side)); }
            case "inst" -> { var pair=endpoints((Co)f.getFirst());var argument=endpoints((Co)f.get(1));yield List.of(piApply(pair.getFirst(),List.of(argument.getFirst())),piApply(pair.get(1),List.of(argument.get(1)))); }
            case "kind" -> { var pair=endpoints((Co)f.getFirst());yield List.of(kind(pair.getFirst()),kind(pair.get(1))); }
            case "sub" -> endpoints((Co)f.getFirst());
            case "axiom" -> axiomEndpoints(list(f.getFirst()),list(f.get(1)));
            default -> throw new IllegalArgumentException("Unknown coercion " + coercion.tag);
        };
    }
    private Ty select(List<?> selector,Ty type){
        int tag=number(selector.getFirst());
        if(tag==0){if(type instanceof Con con)return con.arguments.get(number(selector.get(1))).type;if(type instanceof Tuple tuple)return tuple.arguments.get(number(selector.get(1))).type;}
        if(tag==1&&type instanceof ForAll forall)return forall.variable.kind;
        if(type instanceof Fun fun)return switch(tag){case 2->fun.multiplicity;case 3->fun.argument;case 4->fun.result;default->throw new IllegalArgumentException("Invalid function selector "+selector);};
        throw new IllegalArgumentException("Coercion selector has incompatible endpoint "+type);
    }
    private List<Ty> splitApplication(Ty type){
        type=view(type,false);
        if(type instanceof Fun fun){String occurrence=switch(fun.flag){case 0->"FUN";case 1->"-=>";case 2->"=>";case 3->"==>";default->throw new IllegalArgumentException("Invalid function flag");};var args=new ArrayList<Arg>();if(fun.flag==0)args.add(new Arg(fun.multiplicity,1));args.add(new Arg(runtimeRep(fun.argument),1));args.add(new Arg(runtimeRep(fun.result),1));args.add(new Arg(fun.argument,0));return List.of(new Con(builtin("GHC.Internal.Prim",3,occurrence),false,new Sort(0,0,0),args),fun.result);}
        if(type instanceof Con family){Declaration d=declarations.apply(family.name);if(d!=null&&d.form.equals("family")&&family.arguments.size()<=d.parameters.size())throw new IllegalArgumentException("Cannot unsaturate type family "+CoreHiNames.id(family.name));}
        if(type instanceof Con con&&!con.arguments.isEmpty()){int n=con.arguments.size();return List.of(new Con(con.name,con.promoted,con.sort,con.arguments.subList(0,n-1)),con.arguments.getLast().type);}
        if(type instanceof App app&&!app.arguments.isEmpty()){int n=app.arguments.size();return List.of(apply(app.head,app.arguments.subList(0,n-1)),app.arguments.getLast().type);}
        throw new IllegalArgumentException("Cannot split type application "+type);
    }
    private List<Ty> axiomEndpoints(List<?> rule,List<?> arguments){
        if (number(rule.getFirst()) == 0) {
            var pairs = new ArrayList<List<Ty>>();
            for (Object argument : arguments) pairs.add(endpoints((Co) argument));
            return CoreHiAxioms.endpoints(this, (String) rule.get(1), pairs);
        }
        var name=(CoreHiReader.ExternalName)rule.get(1);Axiom axiom=axioms.apply(name);
        if(axiom==null)throw new IllegalArgumentException("Missing native coercion axiom "+CoreHiNames.id(name));
        Branch branch=axiom.branches.get(number(rule.getFirst())==1?0:number(rule.get(2)));
        if(arguments.size()!=branch.variables.size())throw new IllegalArgumentException("Coercion axiom argument count mismatch "+CoreHiNames.id(name));
        var leftTypes=new HashMap<Variable,Ty>();var rightTypes=new HashMap<Variable,Ty>();var leftCos=new HashMap<Variable,Co>();var rightCos=new HashMap<Variable,Co>();
        for(int i=0;i<arguments.size();i++){Variable v=branch.variables.get(i);var pair=endpoints((Co)arguments.get(i));
            if(v.coercion){leftCos.put(v,((CoTy)pair.getFirst()).coercion);rightCos.put(v,((CoTy)pair.get(1)).coercion);}else{leftTypes.put(v,pair.getFirst());rightTypes.put(v,pair.get(1));}}
        Ty lhs=con(axiom.tycon,false,branch.lhs.toArray(Ty[]::new));
        return List.of(substitute(lhs,leftTypes,leftCos),substitute(branch.rhs,rightTypes,rightCos));
    }
    private Ty synonymView(Ty input) {
        Ty type=input;var seen=new HashSet<Ty>();
        while(true){
            if(type instanceof Tuple tuple)type=tupleConstructor(tuple);
            if(type instanceof App app&&app.head instanceof Con)type=apply(app.head,app.arguments);
            if(type instanceof Con candidate){Ty smart=smartCon(candidate);if(!(smart instanceof Con))return smart;}
            if(!(type instanceof Con con))return type;
            var family=CoreHiNames.tupleFamily(con.name);
            if(family!=null&&con.sort.tag==0)type=con=new Con(con.name,con.promoted,new Sort(1,family.arity(),family.sort()),con.arguments);
            var sum=CoreHiNames.sumFamily(con.name);if(sum!=null&&sum.alternative()==-1&&con.sort.tag==0)type=con=new Con(con.name,con.promoted,new Sort(2,sum.arity(),0),con.arguments);
            Declaration declaration=declarations.apply(con.name);
            if(declaration==null)throw new IllegalArgumentException("Missing native type declaration "+CoreHiNames.id(con.name));
            if(!declaration.form.equals("synonym")||con.arguments.size()<declaration.parameters.size())return type;
            if(!seen.add(type))throw new IllegalArgumentException("Cyclic type synonym "+CoreHiNames.id(con.name));
            var substitution=new HashMap<Variable,Ty>();for(int i=0;i<declaration.parameters.size();i++)substitution.put(declaration.parameters.get(i).variable,con.arguments.get(i).type);
            type=apply(substitute(declaration.rhs,substitution),con.arguments.subList(declaration.parameters.size(),con.arguments.size()));
        }
    }
    private Ty innerType(Ty input) {
        Ty type=input;while(true){type=synonymView(type);if(type instanceof ForAll forall)type=forall.body;else if(type instanceof Cast cast)type=cast.type;else return type;}
    }
    Ty view(Ty input,boolean newtypes) {
        if(!newtypes)return synonymView(input);
        Ty original=innerType(input),type=original;var recursive=new HashMap<CoreHiReader.ExternalName,Integer>();
        while(type instanceof Con con){
            Declaration declaration=declarations.apply(con.name);
            if(declaration==null)throw new IllegalArgumentException("Missing native type declaration "+CoreHiNames.id(con.name));
            if(!declaration.form.equals("newtype"))return type;
            Axiom axiom=declaration.axiom;
            if(axiom==null||axiom.branches.isEmpty())throw new IllegalArgumentException("Newtype lacks its declaration axiom "+CoreHiNames.id(con.name));
            Branch branch=axiom.branches.getFirst();if(con.arguments.size()<branch.lhs.size())return type;
            // Match GHC RecTcChecker's encounter bound and abort the whole normalization.
            if(recursive.merge(con.name,1,Integer::sum)>100)return original;
            var substitution=new HashMap<Variable,Ty>();for(int i=0;i<branch.lhs.size();i++)match(branch.lhs.get(i),con.arguments.get(i).type,new HashSet<>(branch.variables),substitution);
            type=innerType(apply(substitute(branch.rhs,substitution),con.arguments.subList(branch.lhs.size(),con.arguments.size())));
        }
        return type;
    }
    /** GHC type equality: normalize synonyms/tuple syntax, then compare with paired lexical identities.
     * Coercion proofs are irrelevant only after checking their kinds. */
    boolean equal(Ty left, Ty right) { return equal(left, right, new HashMap<>(), new HashSet<>()); }
    private boolean equal(Ty left, Ty right, Map<Variable,Variable> names, Set<Variable> rightBound) {
        left = view(left, false); right = view(right, false);
        if (left instanceof Cast a || right instanceof Cast) {
            if (!equal(kind(left), kind(right), names, rightBound)) return false;
            return equal(left instanceof Cast a ? a.type : left, right instanceof Cast b ? b.type : right, names, rightBound);
        }
        if (left instanceof CoTy && right instanceof CoTy) return equal(kind(left), kind(right), names, rightBound);
        if (left instanceof Var a && right instanceof Var b) return names.containsKey(a.variable)
                ? names.get(a.variable).equals(b.variable) : !rightBound.contains(b.variable) && a.variable.equals(b.variable);
        if (left instanceof ForAll a && right instanceof ForAll b) {
            if ((a.visibility == 0) != (b.visibility == 0) || a.variable.coercion != b.variable.coercion || !equal(a.variable.kind,b.variable.kind,names,rightBound)) return false;
            var nested = new HashMap<>(names); nested.put(a.variable,b.variable); var bound = new HashSet<>(rightBound); bound.add(b.variable);
            return equal(a.body,b.body,nested,bound);
        }
        if (left instanceof Con a && right instanceof Con b) return a.name.equals(b.name) && a.promoted == b.promoted && equalArgs(a.arguments,b.arguments,names,rightBound);
        if (left instanceof App a && right instanceof App b) return equal(a.head,b.head,names,rightBound) && equalArgs(a.arguments,b.arguments,names,rightBound);
        if (left instanceof Fun a && right instanceof Fun b) return equal(a.multiplicity,b.multiplicity,names,rightBound) && equal(a.argument,b.argument,names,rightBound) && equal(a.result,b.result,names,rightBound);
        return left instanceof Lit a && right instanceof Lit b && a.equals(b);
    }
    private boolean equalArgs(List<Arg> a,List<Arg> b,Map<Variable,Variable> names,Set<Variable> rightBound) {
        if(a.size()!=b.size())return false;
        for(int i=0;i<a.size();i++)if(!equal(a.get(i).type,b.get(i).type,names,rightBound))return false;
        return true;
    }
    private static void match(Ty pattern,Ty value,Set<Variable> variables,Map<Variable,Ty> substitution){
        if(pattern instanceof Var var&&variables.contains(var.variable)){Ty old=substitution.putIfAbsent(var.variable,value);if(old!=null&&!alphaEquals(old,value))throw new IllegalArgumentException("Inconsistent axiom type instantiation");return;}
        if(pattern instanceof Con p&&value instanceof Con v&&p.name.equals(v.name)&&p.promoted==v.promoted&&p.arguments.size()==v.arguments.size()){
            for(int i=0;i<p.arguments.size();i++)match(p.arguments.get(i).type,v.arguments.get(i).type,variables,substitution);return;}
        if(!alphaEquals(pattern,value))throw new IllegalArgumentException("Axiom pattern does not match supplied type "+pattern+" / "+value);
    }
    static boolean alphaEquals(Ty left,Ty right){return alphaEquals(left,right,new HashMap<>());}
    private static boolean alphaEquals(Ty left,Ty right,Map<Variable,Variable> names){
        if(left instanceof Var a&&right instanceof Var b)return names.containsKey(a.variable) ? names.get(a.variable).equals(b.variable) : !names.containsValue(b.variable) && a.variable.equals(b.variable);
        if(left instanceof ForAll a&&right instanceof ForAll b){if((a.visibility==0)!=(b.visibility==0)||a.variable.coercion!=b.variable.coercion||!alphaEquals(a.variable.kind,b.variable.kind,names))return false;var nested=new HashMap<>(names);nested.put(a.variable,b.variable);return alphaEquals(a.body,b.body,nested);}
        if(left instanceof Con a&&right instanceof Con b)return a.name.equals(b.name)&&a.promoted==b.promoted&&alphaArgs(a.arguments,b.arguments,names);
        if(left instanceof App a&&right instanceof App b)return alphaEquals(a.head,b.head,names)&&alphaArgs(a.arguments,b.arguments,names);
        if(left instanceof Fun a&&right instanceof Fun b)return a.flag==b.flag&&alphaEquals(a.multiplicity,b.multiplicity,names)&&alphaEquals(a.argument,b.argument,names)&&alphaEquals(a.result,b.result,names);
        if(left instanceof Tuple a&&right instanceof Tuple b)return a.sort==b.sort&&a.promoted==b.promoted&&alphaArgs(a.arguments,b.arguments,names);
        if(left instanceof Lit a&&right instanceof Lit b)return a.equals(b);
        if(left instanceof Cast a)return alphaEquals(a.type,right instanceof Cast b?b.type:right,names);
        if(right instanceof Cast b)return alphaEquals(left,b.type,names);
        if(left instanceof CoTy a&&right instanceof CoTy b)return a.equals(b); // Semantic proof irrelevance requires equal(), which checks kinds.
        return false;
    }
    private static boolean alphaArgs(List<Arg>a,List<Arg>b,Map<Variable,Variable>names){if(a.size()!=b.size())return false;for(int i=0;i<a.size();i++)if(!alphaEquals(a.get(i).type,b.get(i).type,names))return false;return true;}


    /** Exact nominal identity in THC's existing typed foreign annotation schema.
     * This is serialization, not synonym/newtype normalization or ABI inference. */
    Map<String,Object> foreignTypeIdentity(Ty type) { return foreignTypeIdentity(type,List.of()); }
    private Map<String,Object> foreignTypeIdentity(Ty type,List<Variable> bound) {
        if(type instanceof Tuple tuple)type=tupleConstructor(tuple);
        if(type instanceof Con con){
            String namespace=switch(con.name.namespace()){case 0->"value";case 1->"data";case 3->"type";default->throw new IllegalArgumentException("Foreign annotation type has unsupported name namespace");};
            var name=Map.of("unit",con.name.module().unit(),"module",con.name.module().name(),"occurrence",con.name.occurrence(),"namespace",namespace);
            return Map.of("kind","tycon","name",name,"arguments",con.arguments.stream().map(a->foreignTypeIdentity(a.type,bound)).toList());
        }
        if(type instanceof App app){
            Map<String,Object> result=foreignTypeIdentity(app.head,bound);
            for(var arg:app.arguments)result=Map.of("kind","application","function",result,"argument",foreignTypeIdentity(arg.type,bound));
            return result;
        }
        if(type instanceof Fun fun&&fun.flag==0)return Map.of("kind","function","multiplicity",foreignTypeIdentity(fun.multiplicity,bound),
            "argument",foreignTypeIdentity(fun.argument,bound),"result",foreignTypeIdentity(fun.result,bound));
        if(type instanceof Var variable){int index=bound.indexOf(variable.variable);if(index>=0)return Map.of("kind","bound-variable","index",(long)index);}
        if(type instanceof ForAll forall){
            var inner=new ArrayList<Variable>();inner.add(forall.variable);inner.addAll(bound);
            return Map.of("kind","forall","binderKind",foreignTypeIdentity(forall.variable.kind,bound),"body",foreignTypeIdentity(forall.body,inner));
        }
        throw new IllegalArgumentException("Foreign annotation schema cannot represent this actual type");
    }
    Ty function(Ty multiplicity,Ty argument,Ty result) { return new Fun(functionFlag(argument,result),multiplicity,argument,result); }
    Ty lambdaType(Variable binder,Ty result) {
        return !binder.coercion || free(result).contains(binder) ? new ForAll(binder,1,result) :
            function(dataCon("Many"),binder.kind,result);
    }
    Ty nominalEquality(Ty left,Ty right){return con(builtin("GHC.Internal.Prim",3,"~#"),false,kind(left),kind(right),left,right);}
    Ty multiplyMultiplicity(Ty left,Ty right){
        Ty one=dataCon("One"),many=dataCon("Many");if(equal(left,one))return right;if(equal(right,one))return left;if(equal(left,many)||equal(right,many))return many;
        return typeCon("GHC.Internal.Types","MultMul",left,right);
    }
    Ty runtimeRep(Ty type) {
        Ty k=view(kind(type),false);
        if(k instanceof Con con && (con.name.equals(builtin("GHC.Internal.Prim",3,"TYPE"))||con.name.equals(builtin("GHC.Internal.Prim",3,"CONSTRAINT"))))return con.arguments.getFirst().type;
        throw new IllegalArgumentException("Type has no runtime representation kind: "+k);
    }
    Object lifted(Ty type) {
        Ty rep=view(runtimeRep(type),false);
        if(rep instanceof Con con&&con.name.equals(builtin("GHC.Internal.Types",1,"BoxedRep"))){Ty levity=view(con.arguments.getLast().type,false);if(levity instanceof Con l){if(l.name.equals(builtin("GHC.Internal.Types",1,"Lifted")))return true;if(l.name.equals(builtin("GHC.Internal.Types",1,"Unlifted")))return false;}return null;}
        // Levity is a property of the RuntimeRep constructor, independent of
        // whether its component storage can already be determined.
        if (rep instanceof Con con) {
            Map<?,?> metadata = CoreHiNames.typeDeclaration(con.name);
            if (metadata != null && con.promoted) {
                Ty resultKind = declaration(metadata).resultKind;
                if (equal(resultKind, typeCon("GHC.Internal.Types", "RuntimeRep"))) return false;
            }
        }
        return null;
    }
    private List<Ty> promotedValues(Ty list) {
        var result=new ArrayList<Ty>();
        while(true){list=view(list,false);if(!(list instanceof Con con))return null;
            if(con.name.equals(builtin("GHC.Internal.Types",1,"[]")))return List.copyOf(result);
            if(!con.name.equals(builtin("GHC.Internal.Types",1,":"))||con.arguments.size()<3)return null;
            result.add(con.arguments.get(con.arguments.size()-2).type);list=con.arguments.getLast().type;}
    }
    private List<String> primitiveReps(Ty rep) {
        rep=view(rep,false);if(!(rep instanceof Con con))return null;
        var module=new CoreHiReader.ModuleId("ghc-internal","GHC.Internal.Types");if(!con.name.module().equals(module))return null;
        String occurrence=con.name.occurrence();
        if(occurrence.equals("BoxedRep")){Ty levity=view(con.arguments.getLast().type,false);return List.of(levity instanceof Con l&&l.name.equals(builtin("GHC.Internal.Types",1,"Lifted"))?"BoxedRep (Just Lifted)":levity instanceof Con l&&l.name.equals(builtin("GHC.Internal.Types",1,"Unlifted"))?"BoxedRep (Just Unlifted)":"BoxedRep Nothing");}
        if(occurrence.equals("TupleRep")){var values=promotedValues(con.arguments.getLast().type);if(values==null)return null;var result=new ArrayList<String>();for(Ty value:values){var reps=primitiveReps(value);if(reps==null)return null;result.addAll(reps);}return List.copyOf(result);}
        if(occurrence.equals("SumRep")){var values=promotedValues(con.arguments.getLast().type);if(values==null)return null;var rows=new ArrayList<List<String>>();for(Ty value:values){var reps=primitiveReps(value);if(reps==null)return null;rows.add(reps);}return sumLayout(rows).reps;}
        if(occurrence.equals("VecRep")){Ty count=view(con.arguments.get(con.arguments.size()-2).type,false),element=view(con.arguments.getLast().type,false);if(!(count instanceof Con n)||!(element instanceof Con e))return null;String suffix=n.name.occurrence().substring(3);return List.of("VecRep "+suffix+" "+e.name.occurrence().replace("Vec","Elem"));}
        return switch(occurrence){case "IntRep","Int8Rep","Int16Rep","Int32Rep","Int64Rep","WordRep","Word8Rep","Word16Rep","Word32Rep","Word64Rep","FloatRep","DoubleRep","AddrRep"->List.of(occurrence);default->null;};
    }
    private record SumLayout(List<String> reps,List<List<Integer>> slots) {}
    private static String sumSlot(String rep){return switch(rep){case "IntRep","WordRep","Int8Rep","Word8Rep","Int16Rep","Word16Rep","Int32Rep","Word32Rep","AddrRep"->"WordRep";case "Int64Rep","Word64Rep"->"Word64Rep";default->rep;};}
    private static final List<String> VECTOR_ELEMENTS=List.of("Int8ElemRep","Int16ElemRep","Int32ElemRep","Int64ElemRep","Word8ElemRep","Word16ElemRep","Word32ElemRep","Word64ElemRep","FloatElemRep","DoubleElemRep");
    private static final List<String> SLOT_ORDER=List.of("BoxedRep (Just Lifted)","BoxedRep (Just Unlifted)","WordRep","Word64Rep","FloatRep","DoubleRep");
    private static int slotCompare(String a,String b){int x=SLOT_ORDER.indexOf(a),y=SLOT_ORDER.indexOf(b);if(x<0)x=SLOT_ORDER.size();if(y<0)y=SLOT_ORDER.size();if(x!=y)return Integer.compare(x,y);if(x<SLOT_ORDER.size())return 0;String[] aa=a.split(" "),bb=b.split(" ");int c=Integer.compare(Integer.parseInt(aa[1]),Integer.parseInt(bb[1]));return c!=0?c:VECTOR_ELEMENTS.indexOf(aa[2])-VECTOR_ELEMENTS.indexOf(bb[2]);}
    private static boolean slotFits(String a,String b){return a.equals(b)||a.equals("WordRep")&&b.equals("Word64Rep");}
    private static SumLayout sumLayout(List<List<String>> alternatives) {
        if (alternatives.size() < 2)
            return new SumLayout(List.of("WordRep"),Collections.nCopies(alternatives.size(),List.of()));
        if (alternatives.stream().flatMap(List::stream).anyMatch(r -> r.equals("BoxedRep Nothing")))
            return new SumLayout(null,null);
        var rows = alternatives.stream().map(row -> row.stream().map(CoreHiTypes::sumSlot).toList()).toList();
        var merged = new ArrayList<String>();
        for (List<String> row : rows) {
            var sorted = new ArrayList<>(row);
            sorted.sort(CoreHiTypes::slotCompare);
            var next = new ArrayList<String>();
            int i = 0, j = 0;
            while (i < merged.size() && j < sorted.size()) {
                String a = merged.get(i), b = sorted.get(j);
                if (slotFits(a,b) || slotFits(b,a)) {
                    next.add(a.equals("Word64Rep") || b.equals("Word64Rep") ? "Word64Rep" : a);
                    i++; j++;
                } else if (slotCompare(a,b) < 0) next.add(merged.get(i++));
                else next.add(sorted.get(j++));
            }
            next.addAll(merged.subList(i,merged.size()));
            next.addAll(sorted.subList(j,sorted.size()));
            merged = next;
        }
        var physical = new ArrayList<String>();
        physical.add("WordRep");
        physical.addAll(merged);
        var projections = new ArrayList<List<Integer>>();
        for (List<String> row : rows) {
            var used = new HashSet<Integer>();
            var selected = new ArrayList<Integer>();
            for (String rep : row) {
                int slot = 1;
                while (slot < physical.size() && (used.contains(slot) || !slotFits(rep,physical.get(slot)))) slot++;
                if (slot == physical.size()) throw new IllegalArgumentException("Invalid sum layout");
                used.add(slot);
                selected.add(slot);
            }
            projections.add(List.copyOf(selected));
        }
        return new SumLayout(List.copyOf(physical),List.copyOf(projections));
    }
    Map<String,Object> representation(Ty original,boolean evaluated) {
        Ty type=view(original,true),rep=runtimeRep(original);List<String> primitives=primitiveReps(rep);var result=new LinkedHashMap<String,Object>();
        result.put("kind","unknown");result.put("primReps",primitives);result.put("evaluated",evaluated||Boolean.FALSE.equals(lifted(original)));
        if(type instanceof Con con && (con.sort.tag==1&&con.sort.tupleSort==1||con.sort.tag==2)){
            int n=con.sort.arity;var children=new ArrayList<Map<String,Object>>();var rows=new ArrayList<List<String>>();boolean known=true;
            for(Arg arg:con.arguments.subList(n,con.arguments.size())){var proof=representation(arg.type,false);children.add(proof);@SuppressWarnings("unchecked")var child=(List<String>)proof.get("primReps");rows.add(child);known&=child!=null;}
            result.put("aggregate",con.sort.tag==1?"unboxed-tuple":"unboxed-sum");result.put(con.sort.tag==1?"components":"alternatives",children);
            if(con.sort.tag==1){result.put("primReps",known?rows.stream().flatMap(List::stream).toList():null);}else{var layout=known?sumLayout(rows):new SumLayout(null,null);result.put("primReps",layout.reps);result.put("tagSlot",0L);result.put("alternativeSlots",layout.slots);}return result;
        }
        Ty normalizedRep=view(rep,false);boolean primitiveType=type instanceof Con tc && declarations.apply(tc.name).form.equals("primitive");if(!primitiveType&&normalizedRep instanceof Con rr&&(rr.name.equals(builtin("GHC.Internal.Types",1,"TupleRep"))||rr.name.equals(builtin("GHC.Internal.Types",1,"SumRep")))){boolean tuple=rr.name.occurrence().equals("TupleRep");result.put("aggregate",tuple?"unboxed-tuple":"unboxed-sum");result.put(tuple?"components":"alternatives",null);if(!tuple){result.put("tagSlot",0L);result.put("alternativeSlots",null);}return result;}
        if(primitives==null)return result;
        if(primitives.isEmpty()){result.put("kind","void");result.put("evaluated",true);return result;}
        if(primitives.size()!=1)return result;
        String primitive=primitives.getFirst();String kind=switch(primitive){case "AddrRep"->"address";case "FloatRep"->"float";case "DoubleRep"->"double";default->primitive.startsWith("BoxedRep")?"object":primitive.startsWith("VecRep ")?"vector":"long";};
        Ty rho=original;while(rho instanceof ForAll forall&&!forall.variable.coercion)rho=forall.body;
        if(primitive.startsWith("BoxedRep")){if(rho instanceof Fun)kind="closure";else if(rho instanceof Con con){Declaration d=declarations.apply(con.name);if(d!=null&&d.form.equals("data"))kind="data";}}
        result.put("kind",kind);if(primitive.startsWith("VecRep ")){String[] vector=primitive.split(" ");result.put("vector",Map.of("lanes",Long.parseLong(vector[1]),"element",vector[2]));}return result;
    }

    /** Context-free shared syntax; lexical variables are bound by read() at their owning scope. */
    static final class Binary {
        private final CoreHiReader reader;
        private final Map<Integer,Object> shared = new HashMap<>();
        private final Set<Integer> active = new HashSet<>();
        Binary(CoreHiReader reader) { this.reader = reader; }
        private static List<Object> node(Object... fields) { return Collections.unmodifiableList(Arrays.asList(fields)); }
        private static boolean bool(CoreHiReader.Cursor c) { int value=c.byteValue();c.require(value<=1,"invalid boolean");return value!=0; }
        private static int visibility(CoreHiReader.Cursor c) {int value=c.byteValue();c.require(value<=2,"invalid forall visibility");return value;}
        private static int role(CoreHiReader.Cursor c){int value=c.byteValue();c.require(value>=1&&value<=3,"invalid coercion role");return value;}
        private List<Object> list(CoreHiReader.Cursor c,int depth,boolean coercions){
            int count=c.count(1);var result=new ArrayList<Object>(count);for(int i=0;i<count;i++)result.add(coercions?coercion(c,depth+1):type(c,depth+1));return List.copyOf(result);
        }
        List<Object> binder(CoreHiReader.Cursor c,int depth){
            int tag=c.byteValue();c.require(tag<=1,"invalid interface binder");
            if(tag==0){type(c,depth+1);String name=reader.fastString(c);return node(name,true,type(c,depth+1));}
            return node(reader.fastString(c),false,type(c,depth+1));
        }
        List<Object> arguments(CoreHiReader.Cursor c,int depth){
            int count=c.count(2);var result=new ArrayList<Object>(count);for(int i=0;i<count;i++){Object type=type(c,depth+1);result.add(node(visibility(c),type));}return List.copyOf(result);
        }
        List<Object> tycon(CoreHiReader.Cursor c){
            var name=CoreHiNames.read(reader,c);boolean promoted=bool(c);int tag=c.byteValue();c.require(tag<=3,"invalid type constructor sort");
            Object sort=switch(tag){case 0,3->node(tag);case 1->{int arity=c.count(0),tuple=c.byteValue();c.require(tuple<=2,"invalid tuple sort");yield node(tag,arity,tuple);}case 2->node(tag,c.count(0));default->throw new AssertionError();};
            return node(name,promoted,sort);
        }
        Object type(CoreHiReader.Cursor c,int depth){
            c.require(depth<256,"type nesting exceeds native Core limit");int tag=c.byteValue();
            if(tag==99){long raw=c.unsigned(32);c.require(raw<reader.sharedTypes.size(),"shared type index out of range");int index=(int)raw;Object known=shared.get(index);if(known!=null)return known;
                c.require(active.add(index),"cyclic shared type");var entry=reader.cursor(reader.sharedTypes.get(index),"shared type "+index);Object result=type(entry,depth+1);entry.expectEnd();active.remove(index);shared.put(index,result);return result;}
            return switch(tag){
                case 0->{Object binder=binder(c,depth+1);int visibility=visibility(c);yield node("forall",binder,visibility,type(c,depth+1));}
                case 1->node("var",reader.fastString(c));
                case 2->node("app",type(c,depth+1),arguments(c,depth+1));
                case 3->{int flag=c.byteValue();c.require(flag<=3,"invalid function type flag");yield node("fun",flag,type(c,depth+1),type(c,depth+1),type(c,depth+1));}
                case 5->{var con=tycon(c);yield node("con",con.get(0),con.get(1),con.get(2),arguments(c,depth+1));}
                case 6->node("cast",type(c,depth+1),coercion(c,depth+1));
                case 7->node("coercion",coercion(c,depth+1));
                case 8->{int tuple=c.byteValue();c.require(tuple<=2,"invalid tuple sort");yield node("tuple",tuple,bool(c),arguments(c,depth+1));}
                case 9->{int literal=c.byteValue();c.require(literal>=1&&literal<=3,"invalid type literal tag");Object value=switch(literal){case 1->integer(c).toString();case 2->reader.fastStringCodePoints(c);case 3->{long cp=c.unsigned(32);c.require(cp<=Character.MAX_CODE_POINT,"invalid type character literal");yield cp;}default->throw new AssertionError();};yield node("lit",literal,value);}
                default->{c.require(false,"invalid IfaceType tag "+tag);throw new AssertionError();}
            };
        }
        Object coercion(CoreHiReader.Cursor c,int depth){
            c.require(depth<256,"coercion nesting exceeds native Core limit");int tag=c.byteValue();return switch(tag){
                case 1->node("refl",type(c,depth+1));
                case 2->{int role=role(c);Object type=type(c,depth+1);int maybe=c.byteValue();c.require(maybe==1||maybe==2,"invalid optional coercion tag");yield node("grefl",role,type,maybe==1?null:coercion(c,depth+1));}
                case 3->node("funco",role(c),coercion(c,depth+1),coercion(c,depth+1),coercion(c,depth+1));
                case 4->{int role=role(c);var con=tycon(c);yield node("conco",role,con.get(0),con.get(1),con.get(2),list(c,depth+1,true));}
                case 5->node("appco",coercion(c,depth+1),coercion(c,depth+1));
                case 6->node("forallco",binder(c,depth+1),visibility(c),visibility(c),coercion(c,depth+1),coercion(c,depth+1));
                case 7->node("varco",reader.fastString(c));
                case 9->{int provenance=c.byteValue();c.require(provenance>=1&&provenance<=3,"invalid universal coercion provenance");Object p=provenance==3?node(provenance,c.string()):node(provenance);yield node("univ",p,role(c),type(c,depth+1),type(c,depth+1),list(c,depth+1,true));}
                case 10->node("sym",coercion(c,depth+1));
                case 11->node("trans",coercion(c,depth+1),coercion(c,depth+1));
                case 12->{int selector=c.byteValue();c.require(selector<=4,"invalid coercion selector");Object s=selector==0?node(selector,c.count(0),role(c)):node(selector);yield node("sel",s,coercion(c,depth+1));}
                case 13->{int side=c.byteValue();c.require(side<=1,"invalid coercion application side");yield node("lr",side,coercion(c,depth+1));}
                case 14->node("inst",coercion(c,depth+1),coercion(c,depth+1));
                case 15->node("kind",coercion(c,depth+1));case 16->node("sub",coercion(c,depth+1));
                case 17->{int rule=c.byteValue();c.require(rule<=2,"invalid axiom rule tag");Object r=rule==0?node(rule,reader.fastString(c)):rule==1?node(rule,CoreHiNames.read(reader,c)):node(rule,CoreHiNames.read(reader,c),c.count(0));yield node("axiom",r,list(c,depth+1,true));}
                default->{c.require(false,"invalid IfaceCoercion tag "+tag);throw new AssertionError();}
            };
        }
        static BigInteger integer(CoreHiReader.Cursor c){
            int tag=c.byteValue();c.require(tag<=2,"invalid GHC Integer tag");if(tag==0)return BigInteger.valueOf(c.signed());int count=c.count(1);c.require(count>0,"empty GHC Integer magnitude");byte[] bytes=new byte[count];for(int i=count-1;i>=0;i--)bytes[i]=(byte)c.byteValue();c.require(bytes[0]!=0,"noncanonical GHC Integer magnitude");BigInteger value=new BigInteger(1,bytes);if(tag==1)value=value.negate();c.require(value.compareTo(BigInteger.valueOf(Long.MIN_VALUE))<0||value.compareTo(BigInteger.valueOf(Long.MAX_VALUE))>0,"noncanonical large GHC Integer");return value;
        }
    }

}
