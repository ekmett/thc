// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.math.BigInteger;
import java.util.*;

/** Compiler-selected built-in coercion rules. Programs preserve distinct argument
 * endpoints; the type engine owns substitution, synonym views and type equality. */
final class CoreHiAxioms {
    private CoreHiAxioms() {}
    private static final BigInteger ZERO = BigInteger.ZERO, ONE = BigInteger.ONE;
    private static final class Rules {
        static final Map<String, Map<?, ?>> entries;
        static {
            var collected = new HashMap<String, Map<?, ?>>();
            for (Object raw : CoreHiNames.wiredAxiomRules()) {
                var rule = (Map<?, ?>) raw;
                String name = (String) rule.get("name");
                if (collected.putIfAbsent(name, rule) != null)
                    throw new IllegalStateException("Duplicate compiler-selected built-in axiom " + name);
            }
            entries = Map.copyOf(collected);
        }
    }
    private static Map<?, ?> rule(String name) {
        var rule = Rules.entries.get(name);
        if (rule == null) throw new IllegalArgumentException("Unknown pinned GHC built-in axiom " + name);
        return rule;
    }
    static int role(String name) { return ((Number) rule(name).get("role")).intValue(); }
    static List<CoreHiTypes.Ty> endpoints(CoreHiTypes types, String name, List<List<CoreHiTypes.Ty>> arguments) {
        var descriptor = rule(name);
        if (arguments.size() != ((List<?>) descriptor.get("argRoles")).size()
                || arguments.stream().anyMatch(pair -> pair.size() != 2))
            throw new IllegalArgumentException("Built-in axiom argument count mismatch " + name);
        Object value = evaluate(types, descriptor.get("program"), arguments);
        if (!(value instanceof List<?> pair) || pair.size() != 2
                || !(pair.get(0) instanceof CoreHiTypes.Ty left) || !(pair.get(1) instanceof CoreHiTypes.Ty right))
            throw new IllegalStateException("Invalid compiler built-in endpoint program " + name);
        return List.of(left, right);
    }
    private static int number(Object value) { return ((Number) value).intValue(); }
    private static CoreHiTypes.Ty type(Object value) { return (CoreHiTypes.Ty) value; }
    private static IllegalArgumentException malformed() {
        return new IllegalArgumentException("Malformed pinned GHC built-in axiom arguments");
    }
    private static Object evaluate(CoreHiTypes types, Object raw, List<List<CoreHiTypes.Ty>> inputs) {
        List<?> expression = (List<?>) raw;
        return switch ((String) expression.getFirst()) {
            case "input" -> inputs.get(number(expression.get(1))).get(number(expression.get(2)));
            case "type" -> types.read(expression.get(1), Map.of());
            case "family" -> {
                var arguments = ((List<?>) expression.get(2)).stream()
                        .map(value -> type(evaluate(types, value, inputs))).toArray(CoreHiTypes.Ty[]::new);
                yield CoreHiTypes.con(CoreHiTypes.name(expression.get(1)), false, arguments);
            }
            case "argument" -> {
                CoreHiTypes.Ty value = types.view(type(evaluate(types, expression.get(4), inputs)), false);
                if (!(value instanceof CoreHiTypes.Con con) || !con.name().equals(CoreHiTypes.name(expression.get(1)))
                        || con.arguments().size() != number(expression.get(2))) throw malformed();
                int index = number(expression.get(3));
                if (index < 0 || index >= con.arguments().size()) throw malformed();
                yield con.arguments().get(index).type();
            }
            case "iscon" -> {
                CoreHiTypes.Ty value = types.view(type(evaluate(types, expression.get(3), inputs)), false);
                yield value instanceof CoreHiTypes.Con con && con.name().equals(CoreHiTypes.name(expression.get(1)))
                        && con.arguments().size() == number(expression.get(2));
            }
            case "equal" -> types.equal(type(evaluate(types, expression.get(1), inputs)), type(evaluate(types, expression.get(2), inputs)));
            case "require" -> {
                if (!Boolean.TRUE.equals(evaluate(types, expression.get(1), inputs))) throw malformed();
                yield evaluate(types, expression.get(2), inputs);
            }
            case "pair" -> List.of(type(evaluate(types, expression.get(1), inputs)), type(evaluate(types, expression.get(2), inputs)));
            case "operation" -> {
                var arguments = ((List<?>) expression.get(2)).stream().map(value -> type(evaluate(types, value, inputs))).toList();
                yield operation(types, (String) expression.get(1), arguments);
            }
            default -> throw new IllegalStateException("Unknown compiler built-in rule expression " + expression.getFirst());
        };
    }
    private static CoreHiTypes.Lit literal(CoreHiTypes types, CoreHiTypes.Ty value, int sort) {
        CoreHiTypes.Ty expanded = types.view(value, false);
        if (!(expanded instanceof CoreHiTypes.Lit lit) || lit.sort() != sort) throw malformed();
        return lit;
    }
    private static BigInteger natural(CoreHiTypes types, CoreHiTypes.Ty value) {
        BigInteger number = (BigInteger) literal(types, value, 1).value();
        if (number.signum() < 0) throw malformed();
        return number;
    }
    private static List<Integer> symbol(CoreHiTypes types, CoreHiTypes.Ty value) {
        return ((List<?>) literal(types, value, 2).value()).stream().map(CoreHiAxioms::number).toList();
    }
    private static int character(CoreHiTypes types, CoreHiTypes.Ty value) {
        int code = number(literal(types, value, 3).value());
        if (code < Character.MIN_CODE_POINT || code > Character.MAX_CODE_POINT) throw malformed();
        return code;
    }
    private static CoreHiTypes.Ty nat(BigInteger value) { return new CoreHiTypes.Lit(1, value); }
    private static CoreHiTypes.Ty str(List<Integer> value) { return new CoreHiTypes.Lit(2, List.copyOf(value)); }
    private static CoreHiTypes.Ty chr(int value) { return new CoreHiTypes.Lit(3, (long) value); }
    private static Object operation(CoreHiTypes types, String operation, List<CoreHiTypes.Ty> values) {
        CoreHiTypes.Ty a = values.getFirst();
        return switch (operation) {
            case "natural" -> nat(natural(types, a));
            case "zero" -> natural(types, a).signum() == 0;
            case "one" -> natural(types, a).equals(ONE);
            case "nonzero" -> natural(types, a).signum() != 0;
            case "greaterOne" -> natural(types, a).compareTo(ONE) > 0;
            case "empty" -> symbol(types, a).isEmpty();
            case "add" -> nat(natural(types, a).add(natural(types, values.get(1))));
            case "multiply" -> nat(natural(types, a).multiply(natural(types, values.get(1))));
            case "subtract" -> {
                BigInteger result = natural(types, a).subtract(natural(types, values.get(1)));
                if (result.signum() < 0) throw malformed();
                yield nat(result);
            }
            case "divide", "modulo", "divideExact" -> {
                BigInteger divisor = natural(types, values.get(1));
                if (divisor.signum() == 0) throw malformed();
                BigInteger[] result = natural(types, a).divideAndRemainder(divisor);
                if (operation.equals("divideExact") && result[1].signum() != 0) throw malformed();
                yield nat(result[operation.equals("modulo") ? 1 : 0]);
            }
            case "power" -> nat(power(natural(types, a), natural(types, values.get(1))));
            case "log2" -> nat(logarithm(natural(types, a), BigInteger.TWO, false));
            case "logExact" -> nat(logarithm(natural(types, a), natural(types, values.get(1)), true));
            case "rootExact" -> nat(root(natural(types, a), natural(types, values.get(1))));
            case "compareNatural", "compareSymbol", "compareChar" -> {
                int comparison = switch (operation) {
                    case "compareNatural" -> natural(types, a).compareTo(natural(types, values.get(1)));
                    case "compareSymbol" -> compareSymbols(symbol(types, a), symbol(types, values.get(1)));
                    default -> Integer.compare(character(types, a), character(types, values.get(1)));
                };
                yield values.get(comparison < 0 ? 2 : comparison == 0 ? 3 : 4);
            }
            case "append" -> {
                var result = new ArrayList<>(symbol(types, a));
                result.addAll(symbol(types, values.get(1)));
                yield str(result);
            }
            case "cons" -> {
                var result = new ArrayList<Integer>();
                result.add(character(types, a)); result.addAll(symbol(types, values.get(1)));
                yield str(result);
            }
            case "stripPrefix", "stripSuffix" -> {
                List<Integer> affix = symbol(types, a), value = symbol(types, values.get(1));
                boolean prefix = operation.equals("stripPrefix");
                if (value.size() < affix.size()) throw malformed();
                int offset = prefix ? 0 : value.size() - affix.size();
                if (!value.subList(offset, offset + affix.size()).equals(affix)) throw malformed();
                yield str(prefix ? value.subList(affix.size(), value.size()) : value.subList(0, offset));
            }
            case "headSymbol", "tailSymbol" -> {
                List<Integer> value = symbol(types, a);
                if (value.isEmpty()) throw malformed();
                yield operation.equals("headSymbol") ? chr(value.getFirst()) : str(value.subList(1, value.size()));
            }
            case "uncons" -> {
                List<Integer> value = symbol(types, a);
                if (value.isEmpty()) yield values.get(1);
                yield types.piApply(values.get(2), List.of(chr(value.getFirst()), str(value.subList(1, value.size()))));
            }
            case "charToNatural" -> nat(BigInteger.valueOf(character(types, a)));
            case "naturalToChar" -> {
                BigInteger code = natural(types, a);
                if (code.compareTo(BigInteger.valueOf(Character.MAX_CODE_POINT)) > 0) throw malformed();
                yield chr(code.intValueExact());
            }
            default -> throw new IllegalStateException("Unknown compiler literal operation " + operation);
        };
    }
    /** GHC compares decoded Char sequences, preserving surrogate codepoints. */
    private static int compareSymbols(List<Integer> left, List<Integer> right) {
        for (int i = 0; i < Math.min(left.size(), right.size()); i++) {
            int compared = Integer.compare(left.get(i), right.get(i));
            if (compared != 0) return compared;
        }
        return Integer.compare(left.size(), right.size());
    }
    /** Exponents retain arbitrary width, including 0^0 = 1 as in GHC. */
    private static BigInteger power(BigInteger base, BigInteger exponent) {
        if (exponent.signum() == 0) return ONE;
        if (base.equals(ZERO) || base.equals(ONE)) return base;
        BigInteger result = ONE;
        while (exponent.signum() != 0) {
            if (exponent.testBit(0)) result = result.multiply(base);
            exponent = exponent.shiftRight(1);
            if (exponent.signum() != 0) base = base.multiply(base);
        }
        return result;
    }
    private static BigInteger logarithm(BigInteger value, BigInteger base, boolean exact) {
        // Literals.genLog: base zero has precisely one accepted input, 1.
        if (base.signum() == 0) { if (value.equals(ONE)) return ZERO; throw malformed(); }
        if (base.equals(ONE) || value.signum() == 0) throw malformed();
        BigInteger result = ZERO;
        boolean integral = true;
        while (value.compareTo(base) >= 0) {
            BigInteger[] division = value.divideAndRemainder(base);
            integral &= division[1].signum() == 0;
            value = division[0]; result = result.add(ONE);
        }
        if (exact && (!integral || !value.equals(ONE))) throw malformed();
        return result;
    }
    private static BigInteger root(BigInteger value, BigInteger degree) {
        if (degree.signum() == 0) throw malformed();
        if (degree.equals(ONE)) return value;
        BigInteger lower = ZERO, upper = value.add(ONE);
        while (upper.subtract(lower).compareTo(ONE) > 0) {
            BigInteger middle = lower.add(upper).shiftRight(1);
            int comparison = boundedPower(middle, degree, value).compareTo(value);
            if (comparison == 0) return middle;
            if (comparison < 0) lower = middle; else upper = middle;
        }
        if (boundedPower(lower, degree, value).equals(value)) return lower;
        throw malformed();
    }
    /** Compare powers exactly without materializing numbers above the search bound. */
    private static BigInteger boundedPower(BigInteger base, BigInteger exponent, BigInteger bound) {
        BigInteger result = ONE;
        while (exponent.signum() != 0) {
            if (exponent.testBit(0)) {
                result = result.multiply(base);
                if (result.compareTo(bound) > 0) return bound.add(ONE);
            }
            exponent = exponent.shiftRight(1);
            if (exponent.signum() != 0) {
                base = base.multiply(base);
                if (base.compareTo(bound) > 0) return bound.add(ONE);
            }
        }
        return result;
    }
}
