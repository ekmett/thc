// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Exact successful, no-stdin receipts from FixtureSupport.runLogged in StackFixtures. */
@SuppressWarnings("unchecked")
public final class OriginalStackFormatterCommands {
    private OriginalStackFormatterCommands() {}
    public static final List<String> labels = List.of("ghc-version", "plugin-build", "original-source-export", "pre-export", "post-export",
        "native-compile", "native-observations", "pre-audit", "post-audit");
    private static final Set<String> keys = Set.of("argv", "environment", "cwd", "exit", "expectedExit", "timeoutSeconds");

    public static List<Map<String, Object>> checked(Object raw) {
        require(raw instanceof List<?> list && list.size() == labels.size(), "Original formatter command count");
        var values = (List<?>) raw;
        var commands = new ArrayList<Map<String, Object>>();
        for (int index = 0; index < values.size(); index++) {
            var label = labels.get(index);
            var value = values.get(index);
            require(value instanceof Map<?, ?> map && map.keySet().equals(keys), "Original formatter " + label + " command fields");
            var command = (Map<String, Object>) value;
            var argv = command.get("argv");
            boolean valid = argv instanceof List<?> args && !args.isEmpty();
            if (valid) for (Object arg : (List<?>) argv) if (!(arg instanceof String text) || text.indexOf('\0') >= 0) valid = false;
            require(valid && !((String) ((List<?>) argv).getFirst()).isEmpty(), "Original formatter " + label + " argv");
            var environment = command.get("environment");
            valid = environment instanceof Map<?, ?>;
            if (valid) for (var entry : ((Map<?, ?>) environment).entrySet()) {
                if (!(entry.getKey() instanceof String key) || key.isEmpty() || key.indexOf('\0') >= 0 ||
                    !(entry.getValue() instanceof String item) || item.indexOf('\0') >= 0) valid = false;
            }
            require(valid, "Original formatter " + label + " environment");
            var cwd = command.get("cwd");
            require(cwd instanceof String text && text.indexOf('\0') < 0 && new File(text).isAbsolute(), "Original formatter " + label + " cwd");
            require(Long.valueOf(0).equals(command.get("exit")) && Long.valueOf(0).equals(command.get("expectedExit")), "Original formatter " + label + " did not succeed");
            long timeout = label.equals("original-source-export") ? 600 : 300;
            require(Long.valueOf(timeout).equals(command.get("timeoutSeconds")), "Original formatter " + label + " timeout");
            commands.add(command);
        }
        // Cached evidence retains its absolute producer cwd; never rewrite its logs.
        var origins = new LinkedHashSet<Object>();
        for (var command : commands) origins.add(command.get("cwd"));
        require(origins.size() == 1, "Original formatter commands have mixed origins");
        return commands;
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
