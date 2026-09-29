// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import thc.*;
import static org.junit.jupiter.api.Assertions.*;

/** A retained real FCall and genuine GHC dictionaries, with a controlled metadata protocol.
 * No SomeException constructor or dictionary is synthesized. */
@Tag("foreign-exceptions-full-core")
@SuppressWarnings("unchecked")
public class PreparedForeignExceptionTest {
    @Test public void metadataFailureUsesEachInvokingInstancesGenuineBridge() throws Exception {
        check(ForeignExceptionFixtureSupport.source("post"));
    }

    @Test public void vectorArrayFailureUsesEachInvokingInstancesGenuineBridge() throws Exception {
        checkVectorArray(ForeignExceptionFixtureSupport.source("post"));
    }

    /** Modeled intrinsic operands, but the exception dictionaries and the JDK
     * bounds failure are real. A shared root must not select a previous load. */
    public static void checkVectorArray(Map<String,Object> source) throws Exception {
        var object = Map.of("kind", "object", "primReps", List.of("BoxedRep (Just Unlifted)"), "evaluated", true);
        var word = Map.of("kind", "long", "primReps", List.of("IntRep"), "evaluated", true);
        var state = Map.of("kind", "void", "primReps", List.of(), "evaluated", true);
        var closure = Map.of("kind", "closure", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", true);
        var tuple = Map.of("kind", "unknown", "aggregate", "unboxed-tuple", "primReps", List.of("BoxedRep (Just Unlifted)"),
            "components", List.of(state, object), "evaluated", true);
        var declaration = Map.of("schema", 1, "convention", "prim", "safety", "safe", "arity", 4, "suppliedArity", 4,
            "target", Map.of("kind", "static", "symbol", "thc_vector_v1_read_java_int_vector", "isFunction", true),
            "argumentReps", List.of(object, object, word, state), "resultRep", tuple);
        var call = List.of("app", List.of("var", "read-vector", Map.of("rep", closure)),
            List.of(List.of("var", "species", Map.of("rep", object)), List.of("var", "array", Map.of("rep", object)),
                List.of("lit", "int", "0", Map.of("rep", word)), List.of("void", Map.of("rep", state))),
            List.of(false, false, false, false), false, false, Map.of("rep", tuple, "foreignCall", declaration));
        var body = List.of("case", call, "pair", List.of(List.of("data", "vector-tuple", List.of("s", "v"),
            List.of("var", "v", Map.of("rep", object)), Map.of("binders", List.of(
                Map.of("id", "s", "lifted", false, "rep", state), Map.of("id", "v", "lifted", false, "rep", object))))),
            Map.of("rep", object, "binder", Map.of("id", "pair", "lifted", false, "rep", tuple)));
        var binding = Map.<String,Object>of("id", "inspect-vector", "name", "inspect-vector", "lifted", true, "arity", 2, "rep", closure,
            "expr", List.of("lam", List.of(Map.of("id", "species", "lifted", false, "rep", object),
                Map.of("id", "array", "lifted", false, "rep", object)), body, Map.of("rep", closure, "resultRep", object)));
        var input = new LinkedHashMap<>(source);
        var bindings = new ArrayList<>((List<Map<String,Object>>)source.get("bindings")); bindings.add(binding); input.put("bindings", bindings);
        var constructors = new ArrayList<>((List<Map<String,Object>>)source.get("constructors"));
        constructors.add(Map.of("id", "vector-tuple", "name", "(#,#)", "kind", "unboxed-tuple", "arity", 2, "tag", 1));
        input.put("constructors", constructors);
        var linked = CoreModules.reachable(input, "inspect-vector", true);
        linked.put("instrument", true);
        try (var engine = Engine.newBuilder().allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false")
                .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build()) {
            Program.PreparedCode code;
            try (var context = Context.newBuilder("thc").engine(engine).build()) {
                context.initialize("thc"); context.enter();
                try { code = Program.prepareCode(TruffleLanguage.LanguageReference.create(Language.class).get(null), linked, List.of("inspect-vector")); }
                finally { context.leave(); }
            }
            com.oracle.truffle.api.RootCallTarget shared = null;
            for (int load = 0; load < 2; load++) try (var context = Context.newBuilder().engine(engine)
                    .allowNativeAccess(true).allowIO(org.graalvm.polyglot.io.IOAccess.ALL).allowCreateThread(true).build()) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    var owner = Language.currentState();
                    for (var link : code.getForeignLinks()) owner.cbits().link(link);
                    for (var link : code.getPackageScalarLinks()) owner.getPackageCbits().link(link);
                    var instances = new ArrayList<Program>();
                    for (int instance = 0; instance < 2; instance++) {
                        var program = code.newInstance(language);
                        instances.add(program);
                        var entry = (Closure) program.entryValue("inspect-vector");
                        var target = (com.oracle.truffle.runtime.OptimizedCallTarget) entry.target;
                        if (shared == null) {
                            shared = entry.target;
                            assertFalse(target.wasExecuted());
                            assertTrue(target.prepareForAOT());
                            assertFalse(target.wasExecuted());
                        } else assertSame(shared, entry.target);
                        target.compile(true); assertTrue(target.isValidLastTier());
                        var failure = assertThrows(GuestException.class, () -> ScalarTestCalls.callScalarTestTarget(entry.target,
                            new Object[]{0L, entry.environment, jdk.incubator.vector.IntVector.SPECIES_128, new int[0]}));
                        assertTrue(failure.getSomeException());
                        var exit = new RootNode(language) {
                            @Child private ForeignExceptionAccess access = new ForeignExceptionAccess();
                            @Override public Object execute(VirtualFrame frame) { throw access.escaping((GuestException)frame.getArguments()[0]); }
                        }.getCallTarget();
                        var foreign = assertThrows(com.oracle.truffle.api.exception.AbstractTruffleException.class, () -> exit.call(failure));
                        assertTrue(owner.getEnv().isHostException(foreign));
                        assertInstanceOf(IndexOutOfBoundsException.class, owner.getEnv().asHostException(foreign));
                        assertSame(foreign, assertThrows(com.oracle.truffle.api.exception.AbstractTruffleException.class, () -> exit.call(failure)));
                        var good = (jdk.incubator.vector.IntVector) ScalarTestCalls.callScalarTestTarget(entry.target,
                            new Object[]{0L, entry.environment, jdk.incubator.vector.IntVector.SPECIES_128, new int[]{11, 22, 33, 44}});
                        assertArrayEquals(new int[]{11, 22, 33, 44}, good.toArray());
                        assertEquals(0, program.diagnostics().get("loweredRootCount"));
                        // Species/array profiles may adapt on the cold fault.
                        // Separately check compiled execution after those inputs
                        // have been observed; this is not a cold-retention claim.
                        target.compile(true); assertTrue(target.isValidLastTier());
                        good = (jdk.incubator.vector.IntVector) ScalarTestCalls.callScalarTestTarget(entry.target,
                            new Object[]{0L, entry.environment, jdk.incubator.vector.IntVector.SPECIES_128, new int[]{55, 66, 77, 88}});
                        assertArrayEquals(new int[]{55, 66, 77, 88}, good.toArray());
                        assertTrue(((Number)program.diagnostics().get("compiledEntries")).longValue() > 0,
                            "load=" + load + " instance=" + instance + " " + program.diagnostics());
                        assertEquals(instance + 1, owner.getForeignExceptionRegistry().snapshot().size());
                    }
                    java.lang.ref.Reference.reachabilityFence(instances);
                } finally { context.leave(); }
            }
        }
    }

    public static void check(Map<String,Object> source) throws Exception {
        final Map<String,Object> declaration;
        try (var input = Objects.requireNonNull(PreparedForeignExceptionTest.class.getResourceAsStream("/core/foreign-exception-descriptor.json"))) {
            declaration = (Map<String,Object>)Json.parse(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        var proofs = (List<Map<String,Object>>)declaration.get("argumentReps");
        var address = proofs.get(0); var selector = proofs.get(1); var word = proofs.get(2); var state = proofs.get(3);
        var closure = Map.of("kind", "closure", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", true);
        var tuple = (Map<String,Object>)declaration.get("resultRep");
        var operand = List.of("var", "handle", Map.of("rep", address));
        var call = List.of("app", List.of("var", "foreign-metadata", Map.of("rep", closure)),
            List.of(operand, List.of("lit", "int32", "1", Map.of("rep", selector)),
                List.of("lit", "int64", "-1", Map.of("rep", word)), List.of("void", Map.of("rep", state))),
            List.of(false, false, false, false), false, false, Map.of("rep", tuple, "foreignCall", declaration));
        var body = List.of("case", call, "pair", List.of(List.of("data", "metadata-tuple", List.of("s", "n"),
            List.of("var", "n", Map.of("rep", word)), Map.of("binders", List.of(
                Map.of("id", "s", "lifted", false, "rep", state), Map.of("id", "n", "lifted", false, "rep", word))))),
            Map.of("rep", word, "binder", Map.of("id", "pair", "lifted", false, "rep", tuple)));
        var binding = Map.<String,Object>of("id", "inspect-metadata", "name", "inspect-metadata", "lifted", true, "arity", 1, "rep", closure,
            "expr", List.of("lam", List.of(Map.of("id", "handle", "lifted", false, "rep", address)), body, Map.of("rep", closure, "resultRep", word)));
        var input = new LinkedHashMap<>(source);
        var bindings = new ArrayList<>((List<Map<String,Object>>)source.get("bindings")); bindings.add(binding); input.put("bindings", bindings);
        var constructors = new ArrayList<>((List<Map<String,Object>>)source.get("constructors"));
        constructors.add(Map.of("id", "metadata-tuple", "name", "(#,#)", "kind", "unboxed-tuple", "arity", 2, "tag", 1));
        input.put("constructors", constructors);
        var linked = CoreModules.reachable(input, "inspect-metadata", true);
        try (var engine = Engine.newBuilder().allowExperimentalOptions(true).option("engine.Compilation", "false").build()) {
            Program.PreparedCode code;
            try (var context = Context.newBuilder("thc").engine(engine).build()) {
                context.initialize("thc"); context.enter();
                try { code = Program.prepareCode(TruffleLanguage.LanguageReference.create(Language.class).get(null), linked, List.of("inspect-metadata")); }
                finally { context.leave(); }
            }
            for (int load = 0; load < 2; load++) try (var context = Context.newBuilder().engine(engine)
                    .allowNativeAccess(true).allowIO(org.graalvm.polyglot.io.IOAccess.ALL).allowCreateThread(true).build()) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    var owner = Language.currentState();
                    for (var link : code.getForeignLinks()) owner.cbits().link(link);
                    for (var link : code.getPackageScalarLinks()) owner.getPackageCbits().link(link);
                    var instances = new ArrayList<Program>();
                    for (int instance = 0; instance < 2; instance++) {
                        var program = code.newInstance(language);
                        instances.add(program);
                        var secondary = new MetadataProtocolFailure(() -> "secondary metadata failure"); var queries = new AtomicInteger();
                        var original = new MetadataProtocolFailure(() -> { queries.incrementAndGet(); throw secondary; });
                        var origin = new ForeignFailure(owner, original, null);
                        var handle = owner.getStablePointers().make(origin);
                        try {
                            var failure = assertThrows(GuestException.class, () -> Calls.target(program.hostEntryTarget(1),
                                new Object[]{program.entryValue("inspect-metadata"), new Object[]{handle}}));
                            assertTrue(failure.getSomeException()); assertEquals(1, queries.get()); assertNull(origin.getText().get(1));
                            var exit = new RootNode(language) {
                                @Child private ForeignExceptionAccess access = new ForeignExceptionAccess();
                                @Override public Object execute(VirtualFrame frame) { throw access.escaping((GuestException)frame.getArguments()[0]); }
                            }.getCallTarget();
                            assertSame(secondary, assertThrows(MetadataProtocolFailure.class, () -> exit.call(failure)));
                            assertEquals(0, program.diagnostics().get("loweredRootCount"));
                        } finally { owner.getStablePointers().free(handle); }
                        assertThrows(RuntimeFault.class, () -> owner.getStablePointers().dereference(handle));
                        assertEquals(instance + 1, owner.getForeignExceptionRegistry().snapshot().size());
                    }
                    java.lang.ref.Reference.reachabilityFence(instances);
                } finally { context.leave(); }
            }
        }
    }
}
