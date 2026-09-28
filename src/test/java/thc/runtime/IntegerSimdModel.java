// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Independent JVM model: machine arithmetic, no Vector API or runtime primops. */
final class IntegerSimdModel {
    record Input(String name, List<Long> arguments) {}
    record Operands(List<Long> left, List<Long> right) {}
    final String family;
    final boolean signed;
    final int width;
    final int lanes;
    final long mask;
    final List<String> operations;
    final List<String> names;
    final List<Long> weights;
    final List<Long> edges;

    IntegerSimdModel(String family) {
        this.family = family;
        signed = family.startsWith("int");
        width = switch (family) {
            case "int8x16" -> 8;
            case "int16x8", "word16x8" -> 16;
            case "word32x4" -> 32;
            default -> throw new IllegalStateException(family);
        };
        lanes = 128 / width;
        mask = (1L << width) - 1;
        var operations = new ArrayList<>(List.of("plusCase", "minusCase", "timesCase"));
        if (signed) operations.add("negateCase");
        operations.addAll(List.of("packCase", "broadcastCase"));
        this.operations = operations;
        var names = new ArrayList<>(operations);
        names.addAll(List.of("laneCase", "scalarHelperCase", "tupleHelperCase"));
        this.names = names;
        weights = List.of(3L, 5L, 7L, 11L, 13L, 17L, 19L, 23L, 29L, 31L, 37L, 41L, 43L, 47L, 53L, 59L).subList(0, lanes);
        long half = 1L << (width - 1);
        if (signed) {
            long middle = width == 8 ? 65 : 257;
            edges = List.of(-half, -half + 1, -middle, -1L, 0L, 1L, middle, half - 2, half - 1);
        } else edges = List.of(0L, 1L, 2L, half / 2 - 1, half - 1, half, half + 1, mask - 1, mask);
    }
    long narrow(long value) {
        long bits = value & mask;
        return signed ? (bits ^ (1L << (width - 1))) - (1L << (width - 1)) : bits;
    }
    Operands operands(long a, long b) {
        long half = 1L << (width - 1);
        long[] first = {a, b, a + 1, b - 1, a + half - 1, b - half, a * 3 + 7, b * 5 - 11,
            a * 7 + 29, b * 9 - 31, a * 11 + 37, b * 13 - 41, a * 15 + 43, b * 17 - 47, a * 19 + 53, b * 21 - 59};
        long[] second = {b + 2, a - 3, b * 7 + 13, a * 11 - 17, b + half, a - half + 1, b * 13 + 19, a * 17 - 23,
            b * 23 + 61, a * 25 - 67, b * 27 + 71, a * 29 - 73, b * 31 + 79, a * 33 - 83, b * 35 + 89, a * 37 - 97};
        var left = new ArrayList<Long>(lanes);
        var right = new ArrayList<Long>(lanes);
        for (int i = 0; i < lanes; i++) left.add(narrow(first[i]));
        for (int i = 0; i < lanes; i++) right.add(narrow(second[i]));
        return new Operands(left, right);
    }
    List<Long> resultLanes(String name, long a, long b) {
        require(operations.contains(name));
        var pair = operands(a, b);
        var x = pair.left();
        var y = pair.right();
        var result = new ArrayList<Long>(lanes);
        for (int i = 0; i < lanes; i++) result.add(narrow(switch (name) {
            case "plusCase" -> x.get(i) + y.get(i);
            case "minusCase" -> x.get(i) - y.get(i);
            case "timesCase" -> x.get(i) * y.get(i);
            case "negateCase" -> -x.get(i);
            case "packCase" -> x.get(i);
            default -> a - b + 29;
        }));
        return result;
    }
    long expected(String name, List<Long> args) {
        require(names.contains(name) && args.size() == (name.equals("laneCase") ? 4 : 2));
        if (name.equals("laneCase")) {
            require(args.get(0) >= 0 && args.get(0) < operations.size() && args.get(1) >= 0 && args.get(1) < lanes);
            return resultLanes(operations.get(args.get(0).intValue()), args.get(2), args.get(3)).get(args.get(1).intValue());
        }
        String operation = switch (name) {
            case "scalarHelperCase" -> "plusCase";
            case "tupleHelperCase" -> "timesCase";
            default -> name;
        };
        var values = resultLanes(operation, args.get(0), args.get(1));
        long score = 0;
        for (int i = 0; i < lanes; i++) score += weights.get(i) * values.get(i);
        return score + (name.equals("scalarHelperCase") ? 48 : 0);
    }
    Map<Input, Long> parse(String text) {
        var result = new LinkedHashMap<Input, Long>();
        if (!text.isEmpty()) {
            String trimmed = text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
            for (String line : trimmed.split("\n", -1)) {
                var fields = line.split("\t", -1);
                String name = fields[0];
                require(names.contains(name) && fields.length == (name.equals("laneCase") ? 6 : 4), "Unknown name or row arity");
                var arguments = new ArrayList<Long>();
                for (int i = 1; i < fields.length - 1; i++) arguments.add(Long.parseLong(fields[i]));
                require(result.put(new Input(name, arguments), Long.parseLong(fields[fields.length - 1])) == null, "Duplicate native key");
            }
        }
        return result;
    }
    Map<Input, Long> checkedRows(String text) {
        var rows = parse(text);
        for (var row : rows.entrySet()) assertEquals(expected(row.getKey().name(), row.getKey().arguments()),
            row.getValue().longValue(), family + "/" + row.getKey() + " independent model");
        return rows;
    }
    private static void require(boolean condition) { require(condition, "Failed requirement."); }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
}
