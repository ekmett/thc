// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc;

import com.oracle.truffle.api.TruffleLanguage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.graalvm.polyglot.Context;
import thc.runtime.BytecodeProgram;
import thc.runtime.CoreRepresentations;
import thc.runtime.Program;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Direct calls in one exported binding, without following references to other bindings. */
public final class NumericPrimopCoreEvidence {
    private NumericPrimopCoreEvidence() {}

    @SuppressWarnings("unchecked")
    public static void assertLoadableWrappers(Context context, Map<String, Object> module,
                                              List<String> names, String backend) {
        Map<String, Map<String, Object>> bindings = new LinkedHashMap<>();
        for (String name : names) {
            // Preserve complete wrapper dependency admission, including branches
            // that the native input corpus never takes.
            Map<String, Object> reachable = CoreModules.INSTANCE.reachable(module, name, true);
            for (Map<String, Object> binding : (List<Map<String, Object>>) reachable.get("bindings")) {
                bindings.putIfAbsent((String) binding.get("id"), binding);
            }
        }
        Map<String, Object> wrappers = new LinkedHashMap<>(module);
        wrappers.put("bindings", new ArrayList<>(bindings.values()));
        context.initialize("thc");
        context.enter();
        try {
            Language language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
            switch (backend) {
                case "ast" -> new Program(language, wrappers, false, false);
                case "bytecode" -> new BytecodeProgram(language, wrappers);
                default -> throw new IllegalStateException("Unknown numeric primop backend: " + backend);
            }
        } finally {
            context.leave();
        }
    }

    public static List<List<Object>> calls(Map<String, Object> module, String entry) {
        return calls(module, entry, false);
    }

    @SuppressWarnings("unchecked")
    public static List<List<Object>> calls(Map<String, Object> module, String entry, boolean singleBinding) {
        Map<String, Object> reachable = CoreModules.INSTANCE.reachable(module, entry, false);
        List<Map<String, Object>> bindings = (List<Map<String, Object>>) reachable.get("bindings");
        if (singleBinding) assertEquals(1, bindings.size(), entry + " must be one self-contained guest root");
        Map<String, Object> selected = null;
        for (Map<String, Object> binding : bindings) {
            if (entry.equals(binding.get("name"))) {
                if (selected != null) throw new IllegalArgumentException("More than one matching binding: " + entry);
                selected = binding;
            }
        }
        if (selected == null) throw new NoSuchElementException("No matching binding: " + entry);
        List<List<Object>> found = new ArrayList<>();
        visit(selected.get("expr"), found);
        return found;
    }

    @SuppressWarnings("unchecked")
    private static void visit(Object value, List<List<Object>> found) {
        if (value instanceof List<?> values) {
            if (values.size() >= 7 && "app".equals(values.get(0))
                    && values.get(1) instanceof List<?> function
                    && !function.isEmpty() && "prim".equals(function.get(0))) {
                found.add((List<Object>) values);
            }
            for (Object item : values) visit(item, found);
        } else if (value instanceof Map<?, ?> fields) {
            for (Object item : fields.values()) visit(item, found);
        }
    }

    @SuppressWarnings("unchecked")
    public static void assertCall(List<List<Object>> calls, String primitive, List<String> arguments,
                                  String result, String label) {
        List<List<Object>> matches = new ArrayList<>();
        for (List<Object> call : calls) {
            List<?> function = (List<?>) call.get(1);
            if (function.size() > 1 && primitive.equals(function.get(1))) matches.add(call);
        }
        assertEquals(1, matches.size(), label + " must contain the primitive directly exactly once: " + primitive);
        List<Object> app = matches.getFirst();
        List<?> actualArguments = (List<?>) app.get(2);
        assertEquals(arguments.size(), actualArguments.size(), label + "/" + primitive + " arity");
        assertEquals(Collections.nCopies(arguments.size(), false), app.get(3),
            label + "/" + primitive + " value arguments");
        List<List<String>> expectedReps = new ArrayList<>();
        for (String argument : arguments) expectedReps.add(List.of(argument));
        List<List<String>> actualReps = new ArrayList<>();
        for (Object argument : actualArguments) {
            actualReps.add(CoreRepresentations.expression((List<Object>) argument).getPrimReps());
        }
        assertEquals(expectedReps, actualReps, label + "/" + primitive + " argument representations");
        assertEquals(List.of(result), CoreRepresentations.expression(app).getPrimReps(),
            label + "/" + primitive + " result representation");
    }
}
