// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import thc.Json;
import static org.junit.jupiter.api.Assertions.*;

@EnabledOnOs(OS.LINUX)
@EnabledIfSystemProperty(named = "os.arch", matches = "amd64|x86_64")
@SuppressWarnings("unchecked")
public class CoreProcessForeignTest {
    private final Path root = Path.of(System.getProperty("thc.projectRoot"));
    private Object source(String stage) throws Exception { return Json.parse(Files.readString(root.resolve("build/process-lifecycle/core/" + stage + ".json"))); }
    private List<List<Object>> calls(Object value) {
        var result = new ArrayList<List<Object>>();
        if (value instanceof Map<?, ?> map) for (var item : map.values()) result.addAll(calls(item));
        else if (value instanceof List<?> list) {
            if (!list.isEmpty() && "app".equals(list.getFirst()) && list.size() > 6 && list.get(6) instanceof Map<?, ?> meta && meta.get("foreignCall") instanceof Map<?, ?>)
                result.add((List<Object>) list);
            for (var item : list) result.addAll(calls(item));
        }
        return result;
    }
    private ProcessOp validate(List<Object> call) {
        var reps = new ArrayList<Object>();
        for (var argument : (List<List<Object>>) call.get(2)) {
            var metadata = CoreRepresentations.metadata(argument); reps.add(metadata == null ? null : metadata.get("rep"));
        }
        return CoreProcessForeign.validate(call.get(6), reps, (List<?>) call.get(3), ((Map<?, ?>) call.get(6)).get("rep"));
    }
    private Map<String, Object> descriptor(List<Object> call) { return (Map<String, Object>) ((Map<?, ?>) call.get(6)).get("foreignCall"); }
    private List<Object> copy(List<Object> call) { return (List<Object>) Json.parse(Json.stringify(call)); }
    @Test public void genuineInstalledProcessDeclarationsRetainTheirOwnersAndSafety() throws Exception {
        var manifest = (Map<?, ?>) Json.parse(Files.readString(root.resolve("build/process-lifecycle/core/manifest.json")));
        for (var stage : List.of("pre", "post")) {
            var module = source(stage);
            var audit = (Map<?, ?>) Json.parse(Files.readString(root.resolve("build/process-lifecycle/core/" + stage + ".audit.json")));
            assertEquals(true, audit.get("accepted"));
            for (var field : List.of("issues", "missingGlobals", "runtimeExternals")) assertEquals(List.of(), audit.get(field));
            var symbols = new LinkedHashSet<String>(); for (var operation : ProcessOp.values()) symbols.add(operation.getSymbol());
            var actual = new LinkedHashSet<Object>(); for (var call : (List<Map<?, ?>>) audit.get("foreignCalls")) actual.add(call.get("symbol"));
            assertEquals(symbols, actual);
            CoreProcessForeign.validateHeads(module);
            var declarations = calls(module);
            assertEquals(4, declarations.size());
            var operations = new LinkedHashSet<ProcessOp>(); for (var call : declarations) operations.add(Objects.requireNonNull(validate(call)));
            assertEquals(new LinkedHashSet<>(Arrays.asList(ProcessOp.values())), operations);
            for (var call : declarations) {
                var raw = descriptor(call);
                assertEquals(manifest.get("processUnit"), ((Map<?, ?>) raw.get("target")).get("unit"));
                var expected = "waitForProcess".equals(((Map<?, ?>) raw.get("target")).get("symbol")) ? "interruptible" : "unsafe";
                assertEquals(expected, raw.get("safety"));
                assertEquals(expected, Objects.requireNonNull(validate(call)).getSafety());
            }
        }
    }
    @Test public void alteredUnitSafetyConventionArityAndWidthsAreRejected() throws Exception {
        for (var call : calls(source("post"))) {
            for (var unit : List.of("main", "process", "process-1.6.25.0-inplace", "other-1.6.26.1-inplace"))
                reject(call, changed -> ((Map<String, Object>) descriptor(changed).get("target")).put("unit", unit));
            for (var safety : List.of("safe", "unsafe", "interruptible")) if (!safety.equals(descriptor(call).get("safety")))
                reject(call, changed -> descriptor(changed).put("safety", safety));
            reject(call, changed -> descriptor(changed).put("convention", "capi"));
            reject(call, changed -> descriptor(changed).put("arity", 99L));
            reject(call, changed -> descriptor(changed).put("suppliedArity", 0L));
            reject(call, changed -> descriptor(changed).put("schema", true));
            reject(call, changed -> ((List<Object>) changed.get(3)).set(0, true));
            reject(call, changed -> ((List<Map<String, Object>>) descriptor(changed).get("argumentReps")).getFirst().put("primReps", List.of("IntRep")));
            reject(call, changed -> ((Map<String, Object>) descriptor(changed).get("resultRep")).put("primReps", List.of("Word32Rep")));
        }
    }
    private void reject(List<Object> call, Consumer<List<Object>> edit) {
        var changed = copy(call); edit.accept(changed);
        assertThrows(RuntimeFault.class, () -> validate(changed));
    }
}
