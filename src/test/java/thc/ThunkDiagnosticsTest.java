// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.runtime.Calls;
import thc.runtime.Force;
import thc.runtime.Metrics;
import thc.runtime.Thunk;

import static org.junit.jupiter.api.Assertions.*;

public final class ThunkDiagnosticsTest {
    private RootCallTarget unnamed() {
        return new RootNode(null) {
            @Override public Object execute(VirtualFrame frame) {
                throw new IllegalStateException("Recording must not execute the target");
            }
        }.getCallTarget();
    }
    @Test void unnamedRootCountsSerializeWithoutExecutingOrDiscardingThem() {
        var first = unnamed();
        var second = unnamed();
        var named = new RootNode(null) {
            @Override public String getName() { return "unit:Module.named"; }
            @Override public Object execute(VirtualFrame frame) {
                throw new IllegalStateException("Recording must not execute the target");
            }
        }.getCallTarget();
        assertNull(first.getRootNode().getName());
        var metrics = new Metrics(true);
        for (int i = 0; i < 2; i++) metrics.recordThunk(first);
        metrics.recordThunk(second);
        for (int i = 0; i < 4; i++) metrics.recordThunk(named);
        var snapshot = metrics.thunkCountsSnapshot();
        assertEquals(Map.of("<unnamed>", 3L, "unit:Module.named", 4L), snapshot);
        assertEquals(Map.of("thunkEvaluationsByLabel", snapshot),
            Json.INSTANCE.parse(Json.INSTANCE.stringify(Map.of("thunkEvaluationsByLabel", snapshot))));
        metrics.recordThunk(first);
        assertEquals(3L, snapshot.get("<unnamed>"), "Snapshot must not change after subsequent evaluations");
        assertEquals(4L, metrics.thunkCountsSnapshot().get("<unnamed>"));
    }

    @Test void actualUnnamedThunkRetainsOneEvaluationAndItsMemoizedValue() {
        try (Context context = Main.executionContext(false)) {
            context.initialize("thc");
            context.enter();
            try {
                var metrics = new Metrics(true);
                var answer = new Object();
                int[] evaluated = {0};
                var thunk = new Thunk(new RootNode(null) {
                    @Override public Object execute(VirtualFrame frame) { evaluated[0]++; return answer; }
                }.getCallTarget(), null);
                var driver = new RootNode(null) {
                    @Child private Force force = new Force(metrics);
                    @Override public Object execute(VirtualFrame frame) { return force.execute(frame, thunk); }
                }.getCallTarget();
                for (int i = 0; i < 2; i++) assertSame(answer, Calls.target(driver, new Object[0]));
                assertEquals(1, evaluated[0]);
                assertEquals(1L, metrics.getThunkEvaluations());
                assertEquals(1L, metrics.getThunkHits());
                assertNull(thunk.getTarget());
                var diagnostics = Map.of("thunkEvaluations", metrics.getThunkEvaluations(),
                    "thunkEvaluationsByLabel", metrics.thunkCountsSnapshot());
                assertEquals(Map.of("<unnamed>", 1L), diagnostics.get("thunkEvaluationsByLabel"));
                assertEquals(diagnostics, Json.INSTANCE.parse(Json.INSTANCE.stringify(diagnostics)));
            } finally { context.leave(); }
        }
    }

    @Test void nullAndNonStringJsonObjectKeysRemainInvalid() {
        for (Object key : Arrays.asList(null, 1L)) {
            Map<Object, Object> counts = new HashMap<>();
            counts.put(key, 2L);
            var failure = assertThrows(IllegalArgumentException.class, () ->
                Json.INSTANCE.stringify(Map.of("thunkEvaluationsByLabel", counts)));
            assertEquals("JSON object key must be a string", failure.getMessage());
        }
    }
}
