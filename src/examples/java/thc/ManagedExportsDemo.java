// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.util.List;
import org.graalvm.polyglot.Context;

/**
 * Small embedding example for an Int32 declaration, including an IO Int32 result.
 * Example after interface-core preparation:
 * build/interface-core/typed-foreign-exports/managed.json thc-interface-fixture-0.1 ForeignExportManaged thc_next 3
 */
public final class ManagedExportsDemo {
    public static void main(String[] arguments) {
        if (arguments.length < 4) throw new IllegalArgumentException("CORE.json UNIT MODULE C_SYMBOL [INT32 ...]");
        try (Context context = Context.newBuilder("thc").build()) {
            var units = MainKt.loadManagedExports(context, List.of(arguments[0]));
            var function = units.getMember(arguments[1]).getMember(arguments[2]).getMember(arguments[3]);
            Object[] inputs = new Object[arguments.length - 4];
            for (int index = 4; index < arguments.length; index++) inputs[index - 4] = Integer.parseInt(arguments[index]);
            System.out.println(function.execute(inputs));
        }
    }
}
