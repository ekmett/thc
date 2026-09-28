// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import com.oracle.truffle.api.interop.InteropLibrary;
import java.util.*;
import org.graalvm.polyglot.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import thc.runtime.*;
import static org.junit.jupiter.api.Assertions.*;
import static thc.CoreBackendTestSupport.*;

/** Public transport keeps nominal raw references distinct from guest storage. */
class HostInteropAbiTest {
    private static final Map<String, Object> RAW = map("kind", "object", "primReps", list("BoxedRep (Just Unlifted)"), "evaluated", true);
    private static final Map<String, Object> CLOSURE = map("kind", "closure", "primReps", list("BoxedRep (Just Lifted)"), "evaluated", true);
    private static Map<String, Object> type(Object carrier) { return map("rep", RAW, "carriers", list(carrier)); }
    private static Map<String, Object> module(Object carrier) {
        return module(carrier, RAW);
    }
    private static Map<String, Object> module(Object carrier, Map<String, Object> proof) {
        var parameter = map("id", "x", "name", "x", "rep", proof, "lifted", proof.get("primReps").equals(list("BoxedRep (Just Lifted)")));
        var body = list("lam", list(parameter), list("var", "x", map("rep", proof)), map("rep", CLOSURE, "resultRep", proof));
        var binding = map("id", "host:Raw.identity", "arity", 1, "lifted", true, "rep", CLOSURE, "expr", body);
        if (carrier != null) binding.put("hostSignature", map("inputs", list(type(carrier)), "result", type(carrier)));
        return map("schema", 1, "ghc", "9.14.1", "unit", "host", "module", "Raw", "bindings", list(binding), "constructors", list());
    }
    @ParameterizedTest @ValueSource(strings = {"ast", "bytecode"})
    void rawArrayEgressCannotMintOrdinaryGuestElementAuthority(String backend) {
        try (var engine = Engine.create(); var context = context(engine)) {
            var raw = identity(context, backend, "object");
            var guest = context.eval("thc", request(backend, module(null, with(RAW, "primReps", list("BoxedRep (Just Lifted)")))));
            var array = raw.execute((Object) new Object[]{new Object()});
            if (array.hasArrayElements()) {
                var child = array.getArrayElement(0);
                assertThrows(PolyglotException.class, () -> guest.execute(child),
                    "Reading a raw Java array must never turn its element into a proven guest reference");
            }
            assertFalse(array.hasArrayElements());
            assertThrows(UnsupportedOperationException.class, () -> array.getArrayElement(0));
            var bytes = raw.execute((Object) new byte[]{1, 2});
            assertFalse(bytes.hasBufferElements());
            assertThrows(UnsupportedOperationException.class, () -> bytes.readBufferByte(0));
        }
    }
    @ParameterizedTest @ValueSource(strings = {"ast", "bytecode"})
    void rawClosureEgressCannotMintGuestCallableAuthority(String backend) {
        try (var engine = Engine.create(); var context = context(engine)) {
            var raw = identity(context, backend, "object");
            context.enter();
            try {
                var language = com.oracle.truffle.api.TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var integer = CoreRepresentations.parse(map("kind", "long", "primReps", list("IntRep"), "evaluated", true));
                var root = new GuestRoot(language, new FrameLayout().build()) {
                    @Override public long bloom(com.oracle.truffle.api.frame.VirtualFrame frame) { return 0; }
                    @Override public Object execute(com.oracle.truffle.api.frame.VirtualFrame frame) { return frame.getArguments()[1]; }
                };
                root.configureInputProofs(List.of(integer)); root.configureScalarResult(integer);
                var closure = new Closure(null, 1, root.getCallTarget());
                var genuine = context.asValue(HostReference.storage(Language.currentState(), new Object[]{closure}, false));
                assertTrue(genuine.hasArrayElements()); assertTrue(genuine.getArrayElement(0).canExecute());
                assertEquals(19L, genuine.getArrayElement(0).execute(19L).asLong());
                var opaque = raw.execute((Object) closure);
                assertFalse(opaque.canExecute());
                assertThrows(UnsupportedOperationException.class, () -> opaque.execute(19L));
            } finally { context.leave(); }
        }
    }
    private static Value identity(Context context, String backend, Object carrier) {
        return context.eval("thc", request(backend, module(carrier)));
    }
    private static String request(String backend, Map<String, Object> module) {
        return Json.stringify(map("backend", backend, "entry", "host:Raw.identity", "asyncExceptions", false, "modules", list(module)));
    }
    private static Context context(Engine engine) {
        return Context.newBuilder("thc").engine(engine).allowHostAccess(HostAccess.ALL).build();
    }
    @ParameterizedTest @ValueSource(strings = {"ast", "bytecode"})
    void publicRawObjectRoundtripKeepsIdentityWithoutAdmittingGuestArrays(String backend) {
        try (var engine = Engine.create(); var context = context(engine)) {
            var raw = identity(context, backend, "object"); var guest = identity(context, backend, null);
            Object original = new Object(); var exported = raw.execute(original);
            assertEquals(exported, raw.execute(exported));
            assertThrows(PolyglotException.class, () -> guest.execute(original));
            assertThrows(PolyglotException.class, () -> guest.execute(exported));
            context.enter();
            try {
                var owner = Language.currentState();
                var proof = CoreRepresentations.parse(RAW).withHostCarrier(CoreRepresentation.HostCarrier.OBJECT);
                assertSame(original, HostAbi.arguments(owner, List.of(proof), new Object[]{exported})[0]);
                var storage = HostReference.storage(owner, new byte[4], false);
                var roundtrip = raw.execute(context.asValue(storage));
                assertSame(storage, HostAbi.arguments(owner, List.of(proof), new Object[]{roundtrip})[0]);
                assertFalse(InteropLibrary.getUncached().isBufferWritable(storage));
            } catch (com.oracle.truffle.api.interop.UnsupportedMessageException failure) {
                throw new AssertionError(failure);
            } finally { context.leave(); }
        }
    }
    @ParameterizedTest @ValueSource(strings = {"ast", "bytecode"})
    void publicRawReferencesRejectAnotherOwningContext(String backend) {
        try (var engine = Engine.create(); var context = context(engine); var other = context(engine)) {
            var owned = identity(context, backend, "object"); var foreign = identity(other, backend, "object").execute(new Object());
            assertThrows(RuntimeException.class, () -> owned.execute(foreign));
        }
    }
    @ParameterizedTest @ValueSource(strings = {"ast", "bytecode"})
    void dispatcherInputRequiresAcquiredNominalAuthority(String backend) {
        try (var engine = Engine.create(); var context = context(engine)) {
            var library = identity(context, backend, "interop-library"); var raw = identity(context, backend, "object");
            assertThrows(PolyglotException.class, () -> library.execute(new Object()));
            assertThrows(PolyglotException.class, () -> library.execute(raw.execute(new Object())));
        }
    }
    @Test void declarationCannotChangeWorkerRepresentationOrInventCarrierLeaves() {
        for (Object invalid : list(null, map("inputs", list(), "result", type("object")),
                map("inputs", list(type("bogus")), "result", type("object")),
                map("inputs", list(map("rep", RAW, "carriers", list())), "result", type("object")),
                map("inputs", list(map("rep", with(RAW, "primReps", list("BoxedRep (Just Lifted)")), "carriers", list("object"))), "result", type("object")))) {
            var module = module("object"); var binding = objects(module.get("bindings")).getFirst();
            binding.put("hostSignature", invalid);
            assertThrows(RuntimeFault.class, () -> CoreHostSignature.select(binding, objects(module.get("bindings"))));
        }
        var old = module(null); var binding = objects(old.get("bindings")).getFirst();
        assertNull(CoreHostSignature.select(binding, objects(old.get("bindings"))).inputs().getFirst().getHostCarrier());
    }
}
