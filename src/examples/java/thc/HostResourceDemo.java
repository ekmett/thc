// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.lang.ref.Reference;
import java.util.ArrayList;
import java.util.Arrays;
import org.graalvm.polyglot.Value;

/** A Java collection owns a Haskell buffer until the host discards its Value. */
public final class HostResourceDemo {
    // An ordinary Core IO entry takes a null State# and returns [State#, result].
    private static Value io(Value action, Object... arguments) {
        Object[] inputs = Arrays.copyOf(arguments, arguments.length + 1);
        return action.execute(inputs).getArrayElement(1);
    }

    public static void main(String[] arguments) throws InterruptedException {
        if (arguments.length != 2)
            throw new IllegalArgumentException("Usage: HostResourceDemo CORE_INPUTS ENTRY");
        var modules = Arrays.asList(arguments[0].split(","));
        try (var context = Main.executionContext()) {
            var factory = Main.loadEntry(context, modules, arguments[1], false);
            var operations = factory.execute((Object) null);
            var createBuffer = operations.getArrayElement(1);
            var checksum = operations.getArrayElement(2);
            var cleanupCount = operations.getArrayElement(3);
            var resources = new ArrayList<Value>();
            resources.add(io(createBuffer));
            System.gc();
            Thread.sleep(10);
            if (io(cleanupCount).asInt() != 0)
                throw new AssertionError("Host-retained buffer was finalized");
            int total = io(checksum, resources.getFirst()).asInt();
            if (total != 100) throw new AssertionError("Buffer checksum differs");
            System.out.println("Retained buffer: " + total);

            resources.clear();
            int count;
            while ((count = io(cleanupCount).asInt()) == 0) {
                System.gc();
                Thread.sleep(10);
            }
            if (count != 1) throw new AssertionError("Buffer finalized more than once");
            Reference.reachabilityFence(factory);
            Reference.reachabilityFence(operations);
            System.out.println("Released buffer: 1 cleanup");
        }
    }
}
