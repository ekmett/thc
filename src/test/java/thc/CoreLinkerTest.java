// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static thc.CoreFormatTestSupport.*;

class CoreLinkerTest {
    private Map<String, Object> binding(String id, List<Object> expression) { return map("id", id, "name", id, "expr", expression); }
    private List<Object> variable(String id) { return list("var", id); }
    private List<Object> literal() { return list("lit", "int", "0"); }
    @SafeVarargs private final List<?> selected(List<Object> expression, Map<String, Object>... globals) {
        var bindings = new ArrayList<Map<String, Object>>(); bindings.add(binding("root", expression)); bindings.addAll(Arrays.asList(globals));
        var live = CoreModules.reachable(map("bindings", bindings), "root", false);
        return ((List<?>) live.get("bindings")).stream().map(it -> ((Map<?, ?>) it).get("id")).toList();
    }
    private Map<String,Object> runtimeBridge(String unit) {
        String prefix = unit + ":THC.Internal.Exception.";
        return map("schema", 1, "unit", unit, "module", "THC.Internal.Exception",
            "box", prefix + "boxForeign", "project", prefix + "projectForeign", "payloadType", prefix + "ForeignException",
            "exceptionType", "ghc-internal:GHC.Internal.Exception.Type.SomeException");
    }
    @Test void weakRegistrationSelectsItsRuntimeAbiAndTransitiveDependencies() {
        String unit = "selected-runtime", adapter = unit + ":THC.Internal.Weak.runWeakFinalizer";
        String original = "ghc-internal:GHC.Internal.Weak.Finalize.runFinalizerBatch";
        String box = unit + ":THC.Internal.Exception.boxForeign", project = unit + ":THC.Internal.Exception.projectForeign";
        // Graph witnesses only: no guest body or replacement finalizer is executed.
        var module = map("bindings", list(binding("root", list("prim", "mkWeak#")),
            binding(adapter, variable(original)), binding(original, literal()), binding(box, literal()), binding(project, literal()),
            binding("unused", literal())), "foreignExceptionBridges", list(runtimeBridge(unit)), "foreignExceptionBridgeUnit", unit);
        for (var input : List.of(module, with(module, "foreignExceptionBridgeUnit", null))) {
            var linked = CoreModules.reachable(input, "root", true);
            assertEquals(adapter, linked.get("selectedWeakFinalizer"));
            assertEquals(list("root", adapter, original), ((List<?>) linked.get("bindings")).stream()
                .map(it -> ((Map<?,?>) it).get("id")).toList());
        }
        var ambiguous = with(module, "foreignExceptionBridges", list(runtimeBridge(unit), runtimeBridge("another-runtime")));
        assertEquals(adapter, CoreModules.reachable(ambiguous, "root", true).get("selectedWeakFinalizer"));
        assertTrue(assertThrows(RuntimeException.class,
            () -> CoreModules.reachable(with(ambiguous, "foreignExceptionBridgeUnit", null), "root", true))
            .getMessage().contains("ambiguous"));
        assertTrue(assertThrows(IllegalArgumentException.class,
            () -> CoreModules.reachable(with(module, "bindings", list(binding("root", list("prim", "mkWeak#")))), "root", true))
            .getMessage().contains("selected runtime finalizer ABI binding"));
        assertTrue(assertThrows(IllegalArgumentException.class,
            () -> CoreModules.reachable(with(module, "foreignExceptionBridges", list(runtimeBridge("another-runtime")),
                "foreignExceptionBridgeUnit", "another-runtime"), "root", true))
            .getMessage().contains("another-runtime:THC.Internal.Weak.runWeakFinalizer"));
    }
    @Test void looseRuntimeOwnerDiscoveryUsesMetadataWithoutExceptionBodies() {
        var directory = CoreUnitDirectory.read(map("format", "thc-core-packages", "schema", 1L, "ghc", "9.14.1", "units", List.of()));
        var metadata = map("unit", "runtime", "module", "THC.Internal.Exception", "foreignExceptionBridge", runtimeBridge("runtime"));
        assertEquals("runtime", directory.runtimeUnit(ignored -> { throw new AssertionError("No package module is present"); }, List.of(metadata)));
        assertNull(directory.runtimeUnit(ignored -> { throw new AssertionError("No package module is present"); }, List.of()));
        var other = with(metadata, "unit", "other-runtime", "foreignExceptionBridge", runtimeBridge("other-runtime"));
        assertTrue(assertThrows(RuntimeException.class,
            () -> directory.runtimeUnit(ignored -> { throw new AssertionError("No package module is present"); }, List.of(metadata, other)))
            .getMessage().contains("ambiguous"));
        var selected = CoreUnitDirectory.read(map("format", "thc-core-packages", "schema", 1L, "ghc", "9.14.1",
            "foreignExceptionBridgeUnit", "runtime", "units", List.of()));
        assertEquals("runtime", selected.runtimeUnit(ignored -> { throw new AssertionError("Explicit selection needs no metadata"); }, List.of(metadata, other)));
        assertThrows(IllegalArgumentException.class,
            () -> directory.runtimeUnit(ignored -> metadata, List.of(with(metadata, "unit", "wrong-owner"))));
    }
    @Test void programsWithoutHaskellWeakRegistrationDoNotRequireTheRuntimeAdapter() {
        var linked = CoreModules.reachable(map("bindings", list(binding("root", list("prim", "mkWeakNoFinalizer#")))), "root", true);
        assertNull(linked.get("selectedWeakFinalizer"));
        assertEquals(1, ((List<?>) linked.get("bindings")).size());
        var ambiguous = map("bindings", list(binding("root", literal())),
            "foreignExceptionBridges", list(runtimeBridge("first"), runtimeBridge("second")));
        assertNull(CoreModules.reachable(ambiguous, "root", true).get("selectedWeakFinalizer"));
        assertNull(CoreModules.demanded(ambiguous, "root", null,
            () -> { throw new AssertionError("No exception body is needed"); },
            () -> { throw new AssertionError("No runtime owner is needed"); }).get("selectedWeakFinalizer"));
        assertTrue(assertThrows(IllegalArgumentException.class,
            () -> CoreModules.reachable(map("bindings", list(binding("root", list("prim", "mkWeak#")))), "root", true))
            .getMessage().contains("selected THC runtime unit"));
    }
    private Map<String, Object> sourceModule(String id, Map<String, Object> source, Map<String, Object> span) {
        return map("schema", 1, "ghc", "9.14.1", "bindings", list(binding(id, literal())), "constructors", List.of(), "sourceFiles", list(source), "sourceSpans", list(span));
    }
    @Test void linkedModulesPreserveSourceIdentityAndRejectConflictingText() {
        var file = map("id", "shared", "path", "Shared.hs", "content", "entry = 0\n");
        var span = map("id", "entry-span", "file", "shared", "startLine", 1, "startColumn", 1, "endLine", 1, "endColumn", 10, "charIndex", 0, "charLength", 9);
        var linked = CoreModules.reachable(CoreModules.merge(List.of(sourceModule("entry", file, span), sourceModule("unused", file, span))), "entry", false);
        assertEquals(list(file), linked.get("sourceFiles")); assertEquals(list(span), linked.get("sourceSpans"));
        assertThrows(IllegalArgumentException.class, () -> CoreModules.merge(List.of(sourceModule("entry", file, span), sourceModule("other", with(file, "content", "different source"), span))));
    }
    private Map<String, Object> relativeModule(String unit) {
        return map("schema", 1, "ghc", "9.14.1", "unit", unit, "module", "Shared", "bindings", list(binding(unit + ":Shared.entry", literal())), "constructors", List.of(),
            "sourceFiles", list(map("id", "(" + unit + ",src/Shared.hs)", "path", "src/Shared.hs", "content", unit + " source")));
    }
    @Test void sameRelativeSourcePathCanBelongToDistinctGhcUnits() {
        var linked = CoreModules.merge(List.of(relativeModule("pkg-a"), relativeModule("pkg-b"))); var files = (List<?>) linked.get("sourceFiles");
        assertEquals(2, files.size()); var identities = new HashSet<>(); for (var file : files) identities.add(((Map<?, ?>) file).get("id")); assertEquals(2, identities.size());
    }
    @Test void providedInterfaceModulesRequireExactCompleteOwners() {
        var complete = map("schema", 1, "ghc", "9.14.1", "unit", "pkg", "module", "Library", "boundary", "optimized-Core-after-Tidy-before-CorePrep",
            "bindings", list(binding("pkg:Library.value", literal())), "constructors", List.of());
        var fragment = map("schema", 1, "ghc", "9.14.1", "unit", "dependency-closure", "module", "THC.InterfaceClosure", "boundary", "actual-interface-unfoldings",
            "providedModules", list("pkg:Library"), "bindings", List.of(), "constructors", List.of());
        CoreModules.merge(List.of(fragment, complete));
        for (var provider : List.of(with(complete, "unit", "other"), with(complete, "module", "Other"), with(complete, "boundary", "actual-interface-unfoldings")))
            assertTrue(assertThrows(IllegalArgumentException.class, () -> CoreModules.merge(List.of(fragment, provider))).getMessage().contains("exact complete provided modules"));
        assertThrows(IllegalArgumentException.class, () -> CoreModules.merge(List.of(fragment)));
        assertThrows(IllegalArgumentException.class, () -> CoreModules.merge(List.of(fragment, complete, complete)));
        assertThrows(IllegalArgumentException.class, () -> CoreModules.merge(List.of(with(fragment, "unit", "forged"), complete)));
    }
    private Map<String, Object> constructorModule(String name, Map<String, Object> record) {
        return map("schema", 1, "ghc", "9.14.1", "unit", "pkg", "module", name, "bindings", list(binding("pkg:" + name + ".entry", literal())), "constructors", list(record));
    }
    @Test void constructorDisplayTypeMayRenameVariablesButRuntimeMetadataMustMatch() {
        var constructor = map("id", "pkg:Shared.C", "kind", "boxed", "arity", 1, "tag", 2, "fieldReps", list(list("IntRep")), "fieldTypes", list("Int#"),
            "strictFields", list(true), "fieldLifted", list(false), "type", "forall k. C k");
        var first = constructorModule("First", constructor);
        assertEquals(list(constructor), CoreModules.merge(List.of(first, constructorModule("Second", with(constructor, "type", "forall k1. C k1")))).get("constructors"));
        var changes = map("kind", "unboxed-tuple", "arity", 2, "tag", 3, "fieldReps", list(list("WordRep")), "fieldTypes", list("Word#"), "strictFields", list(false), "fieldLifted", list(true), "futureLayout", "different");
        for (var change : changes.entrySet()) assertThrows(IllegalArgumentException.class,
            () -> CoreModules.merge(List.of(first, constructorModule("Second", with(constructor, change.getKey(), change.getValue())))), change.getKey());
    }
    private Map<String, Object> fragment(String id) { return map("schema", 1, "ghc", "9.14.1", "unit", "dependency-closure", "module", "THC.InterfaceClosure", "boundary", "actual-interface-unfoldings", "bindings", list(binding(id, literal())), "constructors", List.of()); }
    @Test void separateInterfaceClosureFragmentsMergeWithoutAdmittingDuplicateDefinitions() {
        assertEquals(2, ((List<?>) CoreModules.merge(List.of(fragment("a"), fragment("b"))).get("bindings")).size());
        assertThrows(IllegalArgumentException.class, () -> CoreModules.merge(List.of(fragment("a"), fragment("a"))));
        assertThrows(IllegalArgumentException.class, () -> CoreModules.merge(List.of(with(fragment("a"), "boundary", "optimized-Core-after-Tidy-before-CorePrep"), with(fragment("b"), "boundary", "optimized-Core-after-Tidy-before-CorePrep"))));
    }
    @Test void shadowedGlobalsDoNotBringTheirUnsupportedDependenciesIntoTheProgram() {
        var lambda = list("lam", list(map("id", "x")), variable("x")); assertEquals(list("root"), selected(lambda, binding("x", variable("unavailable"))));
        var recursive = list("let", true, list(binding("x", variable("y")), binding("y", variable("x"))), variable("x"));
        assertEquals(list("root"), selected(recursive, binding("x", variable("unavailable")), binding("y", literal())));
    }
    @Test void nonrecursiveRhsAndCaseScrutineeRetainTheirOuterDependencies() {
        var nonrecursive = list("let", false, list(binding("x", variable("x"))), variable("x"));
        assertEquals(list("root", "x"), selected(nonrecursive, binding("x", literal())));
        var caseExpression = list("case", variable("x"), "x", list(list("default", null, List.of(), variable("x"))));
        assertEquals(list("root", "x"), selected(caseExpression, binding("x", literal())));
    }
    @Test void recursiveGlobalClosureTerminatesAndKeepsAllTransitiveDependencies() {
        assertEquals(list("root", "x", "y"), selected(variable("x"), binding("x", variable("y")), binding("y", variable("root"))));
    }
    @Test void strictPackageLinkRejectsMissingGlobalAndConstructorBeforeExecution() {
        var missingGlobal = map("bindings", list(binding("dep:App.entry", variable("dep:Lib.missing"))), "constructors", List.of());
        var globalError = assertThrows(IllegalArgumentException.class, () -> CoreModules.reachable(missingGlobal, "dep:App.entry", true)); assertTrue(globalError.getMessage().contains("dep:Lib.missing"));
        var missingConstructor = map("bindings", list(binding("dep:App.entry", list("con", "dep:Lib.C", 0))), "constructors", List.of());
        var constructorError = assertThrows(IllegalArgumentException.class, () -> CoreModules.reachable(missingConstructor, "dep:App.entry", true)); assertTrue(constructorError.getMessage().contains("dep:Lib.C"));
    }
    private List<Object> call(String argument) { return list("app", variable("foreign"), list(variable(argument)), list(false), false, false, map("foreignCall", Map.of())); }
    @SafeVarargs private final Map<String, Object> link(List<Object> expression, Map<String, Object>... extra) {
        var bindings = new ArrayList<Map<String, Object>>(); bindings.add(binding("root", expression)); bindings.addAll(Arrays.asList(extra));
        return CoreModules.reachable(map("bindings", bindings), "root", true);
    }
    @Test void foreignDeclarationsDoNotHideArgumentOrDefinedHeadDependencies() {
        var linked = link(call("dependency"), binding("dependency", variable("transitive")), binding("transitive", literal())); assertEquals(3, ((List<?>) linked.get("bindings")).size());
        assertTrue(assertThrows(IllegalArgumentException.class, () -> link(call("missing-argument"))).getMessage().contains("missing-argument"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> link(call("dependency"), binding("dependency", literal()), binding("foreign", variable("missing-body")))).getMessage().contains("missing-body"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> link(variable("foreign"))).getMessage().contains("foreign"));
    }

}
