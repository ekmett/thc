// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import java.util.ArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InputDispatchThreadTest {
    private static class AddRoot extends GuestRoot {
        private final long amount;
        AddRoot(long amount) {
            super(null, FrameDescriptor.newBuilder().build()); this.amount = amount;
            configureEntry(new boolean[]{false}, false);
        }
        @Override public long bloom(VirtualFrame frame) { return 0L; }
        @Override public Object execute(VirtualFrame frame) { return (Long) frame.getArguments()[1] + amount; }
    }
    private static class CallerRoot extends RootNode {
        @Child private InputDispatch dispatch = new InputDispatch(new ScalarArrayInputSource(null), 1, false, new Metrics(false), null, 0);
        CallerRoot() { super(null); }
        @Override public Object execute(VirtualFrame frame) {
            return dispatch.execute(frame, (Closure) frame.getArguments()[0], new Object[]{frame.getArguments()[1]});
        }
    }
    @Test void concurrentColdArmsAndGenericFallbackPreserveAllTargets() throws Exception {
        var host = new CallerRoot().getCallTarget(); var closures = new ArrayList<Closure>();
        for (long i = 0; i < 5; i++) closures.add(new Closure(null, 1, new AddRoot(i * 17L).getCallTarget()));
        var barrier = new CyclicBarrier(5); var workers = Executors.newFixedThreadPool(5);
        try {
            var calls = new ArrayList<Future<Void>>();
            for (int i = 0; i < 5; i++) {
                int worker = i;
                calls.add(workers.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    for (int step = 0; step < 500; step++) {
                        int index = (worker + step) % closures.size(); long input = worker * 1000L + step;
                        assertEquals(input + index * 17L, Calls.target(host, new Object[]{closures.get(index), input}),
                            "worker=" + worker + " step=" + step + " target=" + index);
                    }
                    return null;
                }));
            }
            for (var call : calls) call.get(15, TimeUnit.SECONDS);
        } finally {
            workers.shutdownNow(); assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS), "Call-site workers did not terminate");
        }
    }
}
