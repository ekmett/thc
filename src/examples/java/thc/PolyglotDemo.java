// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.util.List;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotAccess;

/** Embed an acquired Haskell application with JavaScript in the same context. */
public final class PolyglotDemo {
    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 2)
            throw new IllegalArgumentException("Usage: PolyglotDemo PACKAGE_MANIFEST ENTRY");
        for (String backend : List.of("ast", "bytecode")) {
            System.out.println(backend + ":");
            try (Context context = Main.withContextProfile(Context.newBuilder("thc", "js")
                    .allowNativeAccess(true)
                    .allowPolyglotAccess(PolyglotAccess.ALL), ContextProfile.LAUNCHER).build()) {
                var action = Main.loadEntry(context, List.of("@" + arguments[0]), arguments[1], false, backend, true);
                action.invokeMember("runIO");
            }
        }
    }
}
