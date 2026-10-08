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
            throw new IllegalArgumentException("Usage: HostResourceDemo CORE_INPUTS UNIT:MODULE");
        var modules = Arrays.asList(arguments[0].split(","));
        try (var context = Main.executionContext()) {
            var program = Main.loadProgram(context, modules);
            var counter = io(Main.loadEntry(program, arguments[1] + ".newCounter"));
            var createBuffer = Main.loadEntry(program, arguments[1] + ".newBuffer");
            var checksum = Main.loadEntry(program, arguments[1] + ".checksum");
            var cleanupCount = Main.loadEntry(program, arguments[1] + ".cleanupCount");
            var resources = new ArrayList<Value>();
            resources.add(io(createBuffer, counter));
            System.gc();
            Thread.sleep(10);
            if (io(cleanupCount, counter).asInt() != 0)
                throw new AssertionError("Host-retained buffer was finalized");
            int total = io(checksum, resources.getFirst()).asInt();
            if (total != 100) throw new AssertionError("Buffer checksum differs");
            System.out.println("Retained buffer: " + total);

            resources.clear();
            int count;
            while ((count = io(cleanupCount, counter).asInt()) == 0) {
                System.gc();
                Thread.sleep(10);
            }
            if (count != 1) throw new AssertionError("Buffer finalized more than once");
            // The program, its entry views and the independent observer stay
            // live; only the collection owned the allocated buffer.
            Reference.reachabilityFence(program);
            Reference.reachabilityFence(counter);
            System.out.println("Released buffer: 1 cleanup");
        }
    }
}
