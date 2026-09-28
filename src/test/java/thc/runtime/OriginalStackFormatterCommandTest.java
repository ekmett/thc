// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import thc.Json;
import static org.junit.jupiter.api.Assertions.*;

public class OriginalStackFormatterCommandTest {
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> records() {
        var records = new ArrayList<Map<String, Object>>();
        for (var label : OriginalStackFormatterCommands.labels) records.add(Map.of(
            "argv", List.of("tool", "--flag"), "environment", Map.of("KEY", "value"),
            "cwd", "/producer/checkout", "exit", 0L, "expectedExit", 0L,
            "timeoutSeconds", label.equals("original-source-export") ? 600L : 300L));
        return (List<Map<String, Object>>) Json.parse(Json.stringify(records));
    }
    @Test public void currentSuccessfulReceiptsRetainTheirProducerOrigin() {
        var records = records();
        assertEquals(records, OriginalStackFormatterCommands.checked(records));
    }
    @Test public void incompleteFailedOrUnexpectedReceiptsReject() {
        var records = records();
        for (int index = 0; index < records.size(); index++) {
            var command = records.get(index);
            for (var key : command.keySet()) {
                var changed = new LinkedHashMap<>(command); changed.remove(key); reject(records, index, changed);
            }
            Object[][] badFields = {
                {"exit", 1L}, {"expectedExit", 1L}, {"exit", null}, {"exit", 0.0}, {"expectedExit", 0.0},
                {"timeoutSeconds", 0L}, {"timeoutSeconds", 300.0}, {"cwd", "relative"}, {"cwd", "/other/checkout"},
                {"argv", List.of()}, {"argv", List.of(1L)}, {"environment", Map.of("KEY", 1L)},
                {"stdin", "input"}, {"timedOut", false}
            };
            for (var field : badFields) {
                var changed = new LinkedHashMap<>(command); changed.put((String) field[0], field[1]); reject(records, index, changed);
            }
            var changed = new LinkedHashMap<>(command); changed.put("timeoutSeconds", index == 2 ? 300L : 600L);
            reject(records, index, changed);
        }
        assertThrows(IllegalArgumentException.class, () -> OriginalStackFormatterCommands.checked(records.subList(0, records.size() - 1)));
        assertThrows(IllegalArgumentException.class, () -> OriginalStackFormatterCommands.checked(null));
    }
    private void reject(List<Map<String, Object>> records, int index, Map<String, Object> command) {
        assertThrows(IllegalArgumentException.class, () -> {
            var changed = new ArrayList<>(records); changed.set(index, command); OriginalStackFormatterCommands.checked(changed);
        });
    }
}
