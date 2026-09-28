// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.io.File;
import java.math.BigInteger;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import thc.Json;
import static org.junit.jupiter.api.Assertions.*;

class IntegerSimdModelTest {
    private final List<String> families = List.of("int8x16", "int16x8", "word16x8", "word32x4");
    private final File root = new File(System.getProperty("thc.projectRoot"));

    @Test void originalCorporaAgreeWithIndependentModelAndCompleteLaneGrids() throws Exception {
        var counts = Map.of("int8x16", 9168, "int16x8", 6032, "word16x8", 5116, "word32x4", 4882);
        for (String family : families) {
            var model = new IntegerSimdModel(family);
            var directory = new File(root, "build/simd-" + family);
            var expectedPath = new File(directory, "expected.tsv").toPath();
            var rows = model.checkedRows(Files.readString(expectedPath));
            assertEquals(counts.get(family).intValue(), rows.size());
            var provenance = (Map<?, ?>) Json.parse(Files.readString(new File(directory, "provenance.json").toPath()));
            if (provenance.get("nativeRows") != null)
                assertEquals(rows, model.checkedRows(Files.readString(new File(directory, "oracle.tsv").toPath())));
            for (int op = 0; op < model.operations.size(); op++) for (int lane = 0; lane < model.lanes; lane++) {
                var selected = new ArrayList<IntegerSimdModel.Input>();
                for (var key : rows.keySet()) if (key.name().equals("laneCase")
                        && key.arguments().subList(0, 2).equals(List.of((long) op, (long) lane))) selected.add(key);
                assertEquals(81, selected.size());
                if (model.operations.get(op).equals("broadcastCase")) {
                    var observed = new HashSet<Long>();
                    for (var key : selected) observed.add(rows.get(key));
                    assertEquals(new HashSet<>(model.edges), observed);
                } else {
                    var observed = new HashSet<List<Long>>();
                    for (var key : selected) {
                        var operands = model.operands(key.arguments().get(2), key.arguments().get(3));
                        observed.add(List.of(operands.left().get(lane), operands.right().get(lane)));
                    }
                    var expected = new HashSet<List<Long>>();
                    for (long a : model.edges) for (long b : model.edges) expected.add(List.of(a, b));
                    assertEquals(expected, observed);
                }
            }
            assertThrows(IllegalArgumentException.class, () -> model.parse(Files.readString(expectedPath)
                + Files.readAllLines(expectedPath).getFirst() + "\n"));
            var lines = Files.readAllLines(expectedPath);
            assertNotEquals(rows, model.parse(String.join("\n", lines.subList(1, lines.size()))));
        }
    }

    @Test void narrowingAndUnsignedProductsKeepEveryRetainedEncodingControl() {
        for (String family : families) {
            var m = new IntegerSimdModel(family);
            var samples = new ArrayList<Long>();
            if (m.width == 32) for (long i = 0; i <= 65535; i++) samples.add((i << 16) | ((i * 257 ^ 0xa5a5) & 65535));
            else for (long i = 0; i <= m.mask; i++) samples.add(i);
            assertEquals(samples.size(), new HashSet<>(samples).size());
            if (m.width == 32) {
                var domain = new HashSet<Long>();
                for (long i = 0; i <= 65535; i++) domain.add(i);
                var high = new HashSet<Long>();
                var low = new HashSet<Long>();
                for (long sample : samples) { high.add(sample >>> 16); low.add(sample & 65535); }
                assertEquals(domain, high);
                assertEquals(domain, low);
            }
            for (long bits : samples) {
                long expected = m.signed && bits > m.mask / 2 ? bits - m.mask - 1 : bits;
                assertEquals(expected, m.narrow(bits));
                assertEquals(expected, m.narrow(bits + (1L << 48)));
                if (!m.signed) for (long right : m.edges) {
                    long exact = BigInteger.valueOf(bits).multiply(BigInteger.valueOf(right)).and(BigInteger.valueOf(m.mask)).longValue();
                    assertEquals(exact, m.narrow(bits * right));
                }
            }
            assertEquals(m.signed ? -1 : m.mask, m.narrow(-1));
            assertEquals(0, m.narrow(m.mask + 1));
            assertEquals(1, m.narrow(m.mask * m.mask));
            assertEquals(0, m.narrow(Long.MIN_VALUE));
            assertEquals(m.signed ? -1 : m.mask, m.narrow(Long.MAX_VALUE));
        }
        var bytes = new IntegerSimdModel("int8x16");
        for (long a = -128; a <= 127; a++) for (long b = -128; b <= 127; b++)
            assertEquals((((a * b) & 255) ^ 128) - 128, bytes.resultLanes("timesCase", a, b - 2).getFirst().longValue());
    }

    @Test void laneOrderModuloInputsAndMalformedRowsRemainObservable() {
        for (String family : families) {
            var m = new IntegerSimdModel(family);
            for (String name : m.operations) {
                assertEquals(m.expected(name, List.of(123L, -456L)), m.expected(name, List.of(123 + (1L << 48), -456 - (1L << 32))));
                if (name.equals("broadcastCase")) continue;
                var values = m.resultLanes(name, 12345, -6789);
                assertTrue(new HashSet<>(values).size() > 1);
                long score = 0;
                for (int i = 0; i < values.size(); i++) score += m.weights.get(i) * values.get(i);
                for (int i = 0; i < values.size(); i++) for (int j = 0; j < i; j++) if (!values.get(i).equals(values.get(j))) {
                    var swapped = new ArrayList<>(values);
                    swapped.set(i, values.get(j)); swapped.set(j, values.get(i));
                    long swappedScore = 0;
                    for (int lane = 0; lane < swapped.size(); lane++) swappedScore += m.weights.get(lane) * swapped.get(lane);
                    assertNotEquals(score, swappedScore);
                }
            }
            for (String bad : List.of("unknown\t0\t0\n", "plusCase\t0\t0\n",
                    "plusCase\t9223372036854775808\t0\t0\n", "plusCase\t0\t0\t0\n\n"))
                assertThrows(IllegalArgumentException.class, () -> m.parse(bad));
            for (var args : List.of(List.of(-1L, 0L, 0L, 0L), List.of((long) m.operations.size(), 0L, 0L, 0L),
                    List.of(0L, -1L, 0L, 0L), List.of(0L, (long) m.lanes, 0L, 0L)))
                assertThrows(IllegalArgumentException.class, () -> m.expected("laneCase", args));
        }
    }
}
