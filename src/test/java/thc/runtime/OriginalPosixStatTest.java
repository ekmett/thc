// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.io.IOAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.*;
import thc.*;
import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.function.BiConsumer;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.OriginalStdioChecks.*;
import static thc.runtime.NarrowIntegerCarrierTestKt.callScalarTestTarget;

/** Original imported declarations and native observations, not replacement FFIs. */
@EnabledOnOs(value = OS.LINUX,disabledReason = "Only original Linux stat scalar declarations have native/Core proof")
@SuppressWarnings("unchecked")
class OriginalPosixStatTest {
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final String prefix = "build/original-posix-stat";
    private final List<String> names = List.of("originalStatSize","originalStatDev","originalStatIno","originalStatMode","originalStatLength","originalStatTypes");
    private Map<String,Object> json(String path) throws Exception { return (Map<String,Object>) Json.parse(Files.readString(new File(root,path).toPath())); }
    private Map<String,Object> module(String stage) throws Exception { var modules = new ArrayList<Map<String,Object>>(); for (var part : List.of("OriginalPosixStatAudit","THC.InterfaceClosure")) modules.add(json(prefix + "/" + stage + "/core/" + part + ".json")); return CoreModules.merge(modules); }
    private Object copy(Object value) { return Json.parse(Json.stringify(value)); }
    private void valid(RootCallTarget target) throws Exception { assertEquals(true,target.getClass().getMethod("isValidLastTier").invoke(target)); }
    private Context context() { return Context.newBuilder("thc").allowIO(IOAccess.NONE).allowExperimentalOptions(true).option("engine.BackgroundCompilation","false").option("engine.MultiTier","false").option("engine.CompilationFailureAction","Throw").build(); }
    private Expr operand(List<String> events,String name,Object value) { return new Expr() { @Override public Object execute(VirtualFrame frame) { events.add(name); return value; } }; }
    @Test void statOperandsAndStateKeepTheirOrderBeforeNativeAccess() throws Exception {
        var events = new ArrayList<String>(); var layout = new FrameLayout(); int slot = layout.bind("result"); var frame = Truffle.getRuntime().createVirtualFrame(new Object[0],layout.build());
        var proof = new CoreRepresentation(CoreKind.UNKNOWN,true,true,List.of(),null,null,null,null,null);
        for (var operation : List.of(OriginalStdioOp.SIZEOF_STAT,OriginalStdioOp.IS_DIR,OriginalStdioOp.ST_MODE)) {
            events.clear(); FrameAccess.writeLong(frame,slot,91L);
            Expr[] prefix = switch (operation) { case SIZEOF_STAT -> new Expr[0]; case IS_DIR -> new Expr[]{operand(events,"mode",16384)}; default -> new Expr[]{operand(events,"address",ManagedAddress.nullAddress())}; };
            Expr[] operands = Arrays.copyOf(prefix,prefix.length + 1); operands[prefix.length] = operand(events,"state",9L); var expression = new OriginalStdioExpression(operation,operands,proof);
            var failure = assertThrows(RuntimeFault.class,() -> expression.executeTuple(frame,new int[]{slot},0)); assertTrue(Objects.toString(failure.getMessage(),"").contains("zero-width scalar carrier"),failure.getMessage());
            assertEquals(switch (operation) { case SIZEOF_STAT -> List.of("state"); case IS_DIR -> List.of("mode","state"); default -> List.of("address","state"); },events); assertEquals(91L,FrameAccess.read(frame,slot));
        }
        events.clear(); var expression = new OriginalStdioExpression(OriginalStdioOp.SIZEOF_STAT,new Expr[]{operand(events,"state",kotlin.Unit.INSTANCE)},proof);
        var operands = OriginalStdioExpression.class.getDeclaredField("operands"); operands.setAccessible(true); operands.set(expression,null);
        assertThrows(NullPointerException.class,() -> expression.executeTuple(frame,new int[]{slot},0)); assertTrue(events.isEmpty()); assertEquals(91L,FrameAccess.read(frame,slot));
    }
    @Test void realNativeImagesAndModePredicatesMatchBothBackendsOnFirstInstalledCalls() throws Exception {
        var manifest = json(prefix + "/manifest.json"); assertEquals(1L,manifest.get("schema")); assertEquals("linux",manifest.get("platform")); assertEquals(true,manifest.get("supported"));
        hashes(root,manifest.get("inputHashes"),Set.of("compiler/test-fixtures/OriginalPosixStatAudit.hs","compiler/test-fixtures/OriginalPosixStatNative.hs","test/haskell-fixtures/OriginalPosixStatFixtures.hs","scripts/core_original_foreign.py"));
        var artifacts = new HashSet<>(Set.of(prefix + "/oracle.json")); for (var stage : List.of("pre","post")) { for (var name : names) artifacts.add(prefix + "/" + stage + "/" + name + ".audit.json"); for (var part : List.of("OriginalPosixStatAudit","THC.InterfaceClosure")) artifacts.add(prefix + "/" + stage + "/core/" + part + ".json"); } hashes(root,manifest.get("artifactHashes"),artifacts,prefix + "/");
        var oracle = json(prefix + "/oracle.json"); var images = (List<List<List<Number>>>) oracle.get("images"); var modes = (List<List<Number>>) oracle.get("modes"); assertEquals(6,images.size());
        var expectedModes = new ArrayList<Long>(); for (long mode = 0; mode <= 65535; mode++) expectedModes.add(mode); expectedModes.addAll(List.of(-1L,2147483647L,2147483648L,4294967295L)); var actualModes = new ArrayList<Long>(); for (var row : modes) actualModes.add(row.get(0).longValue()); assertEquals(expectedModes,actualModes);
        for (var stage : List.of("pre","post")) {
            var module = module(stage); for (var name : names) { var audit = json(prefix + "/" + stage + "/" + name + ".audit.json"); assertEquals(true,audit.get("accepted")); assertEquals(List.of(),audit.get("issues")); assertEquals(List.of(),audit.get("missingGlobals")); }
            for (var backend : List.of("ast","bytecode")) try (var context = context()) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    for (var name : names) {
                        var linked = with(CoreModules.reachable(module,name),"instrument",true); var evidence = new ArrayCoreEvidence(linked,name); assertEquals(1,evidence.getBindings().size()); int originalLambdas = List.of("originalStatSize","originalStatTypes").contains(name) ? 1 : 2;
                        assertEquals(originalLambdas,evidence.guestLambdas(evidence.getRoot().get("expr")).size()); assertEquals(1,evidence.loweredGuestLambdas(evidence.getRoot().get("expr")).size(),"Only exact State# redexes lower in-frame");
                        ExecutableProgram program = backend.equals("ast") ? new Program(language,linked) : new BytecodeProgram(language,linked); var entry = program.entryTarget(name);
                        class Exercise {
                            List<RootCallTarget> active = List.of();
                            void invoke(Object argument,long expected,boolean compiled) throws Exception {
                                long before = ((Number) program.diagnostics().get("compiledEntries")).longValue(); assertEquals(expected,Calls.target(entry,new Object[]{0L,argument}),stage + "/" + backend + "/" + name + "/" + argument);
                                if (compiled) { assertEquals(before + 1,((Number) program.diagnostics().get("compiledEntries")).longValue()); assertEquals(active,targets(entry)); for (var target : active) valid(target); }
                                var handoff = language.getHandoffState().get(); assertEquals(0,handoff.getArguments().getDepth()); assertEquals(0,handoff.getResults().getDepth()); assertEquals(0,handoff.getResults().retainedReferences());
                            }
                            void run(boolean compiled) throws Exception {
                                if (name.equals("originalStatSize")) for (long extra : new long[]{-7,0,13}) invoke(extra,((Number) oracle.get("size")).longValue() + extra,compiled);
                                else if (name.equals("originalStatTypes")) for (var row : modes) invoke(row.get(0).longValue(),row.get(1).longValue(),compiled);
                                else { int field = names.indexOf(name) - 1; byte[] bytes = new byte[((Number) oracle.get("size")).intValue() + 7]; var address = ManagedAddress.fromByteArray(bytes).plus(7);
                                    for (var row : images) { assertEquals(bytes.length - 7,row.get(0).size()); for (int i = 0; i < row.get(0).size(); i++) bytes[i + 7] = row.get(0).get(i).byteValue(); invoke(address,row.get(1).get(field).longValue(),compiled); }
                                }
                            }
                        }
                        var exercise = new Exercise(); exercise.run(false); exercise.active = targets(entry); assertEquals(1,exercise.active.size(),stage + "/" + backend + "/" + name + " lowered target shape");
                        for (var target : exercise.active) { target.getClass().getMethod("compile",boolean.class).invoke(target,true); valid(target); } exercise.run(true);
                    }
                } finally { context.leave(); }
            }
        }
    }
    private List<List<Object>> calls() throws Exception {
        var result = new ArrayList<List<Object>>(); var seen = new HashSet<Object>();
        for (var call : foreignCalls(module("pre"))) { var target = (Map<?,?>) ((Map<?,?>) ((Map<?,?>) call.get(6)).get("foreignCall")).get("target"); var symbol = (String) target.get("symbol");
            if ((symbol.equals("__hscore_sizeof_stat") || symbol.startsWith("__hscore_st_") || symbol.contains("ZCSzuIS")) && seen.add(target)) result.add(call); }
        return result;
    }
    private OriginalStdioOp validate(List<Object> call) { var metadata = (Map<?,?>) call.get(6); var reps = new ArrayList<Object>(); for (var arg : (List<List<Object>>) call.get(2)) { var rep = CoreRepresentations.metadata(arg); reps.add(rep == null ? null : rep.get("rep")); } return CoreOriginalStdio.validate(metadata,reps,(List<?>) call.get(3),metadata.get("rep")); }
    Map<String,Object> rawModule(List<Object> original) throws Exception { return rawModule(original,null); }
    Map<String,Object> rawModule(List<Object> original,Integer storedMutation) throws Exception { return OriginalStdioChecks.rawModule(original,module("pre"),storedMutation); }
    @Test void bothLoadersCheckStoredOperandsAndStateBeforeNativeLayoutAccess() throws Exception {
        for (var backend : List.of("ast","bytecode")) try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                class Load { ExecutableProgram call(Map<String,Object> module) { return backend.equals("ast") ? new Program(language,module) : new BytecodeProgram(language,module); }}
                var load = new Load();
                for (var call : calls()) {
                    var operation = Objects.requireNonNull(validate(call)); var program = load.call(rawModule(call)); var target = program.entryTarget("entry"); var args = new ArrayList<Object>();
                    for (var rep : operation.getArguments().subList(0,operation.getArguments().size() - 1)) {
                        if (Objects.equals(rep,"AddrRep")) args.add(ManagedAddress.nullAddress()); else if (NarrowInteger.fromRep(rep) != null) args.add(0); else args.add(0L);
                    }
                    Object[] packet = new Object[args.size() + 2]; packet[0] = 0L; for (int i = 0; i < args.size(); i++) packet[i + 1] = args.get(i); packet[packet.length - 1] = 9L;
                    var failure = assertThrows(RuntimeFault.class,() -> callScalarTestTarget(target,packet)); assertTrue(Objects.toString(failure.getMessage(),"").contains("zero-width scalar carrier"),failure.getMessage());
                    for (int i = 0; i < operation.getArguments().size(); i++) { int index = i; assertThrows(RuntimeFault.class,() -> load.call(rawModule(call,index))); }
                    var malformed = (Map<String,Object>) copy(rawModule(call)); var badCall = single(foreignCalls(malformed),ignored -> true); ((List<Object>) badCall.get(1)).set(1,17L);
                    var badHead = assertThrows(RuntimeFault.class,() -> load.call(malformed)); assertTrue(Objects.toString(badHead.getMessage(),"").startsWith("Invalid original stdio call:"));
                    if (operation.getStatField()) assertThrows(RuntimeFault.class,() -> callScalarTestTarget(target,new Object[]{0L,ManagedAddress.nullAddress(),kotlin.Unit.INSTANCE}));
                    var handoff = language.getHandoffState().get(); assertEquals(0,handoff.getArguments().getDepth()); assertEquals(0,handoff.getResults().getDepth());
                }
            } finally { context.leave(); }
        }
    }
    @Test void genuineDescriptorsRejectWidthSafetyUnitArityFlagsAndAggregateMutations() throws Exception {
        var calls = calls(); assertEquals(11,calls.size()); var operations = new HashSet<OriginalStdioOp>(); for (var call : calls) operations.add(validate(call)); assertEquals(11,operations.size());
        for (var original : calls) {
            var mutate = new Object() { void call(BiConsumer<List<Object>,Map<String,Object>> action) { var call = (List<Object>) copy(original); var descriptor = (Map<String,Object>) ((Map<?,?>) call.get(6)).get("foreignCall"); action.accept(call,descriptor); assertThrows(RuntimeFault.class,() -> validate(call)); }};
            for (var key : List.of("schema","arity","suppliedArity")) for (var wrong : list(null,true,1.0,"1",-1L,4294967296L)) mutate.call((c,d) -> d.put(key,wrong));
            for (var edit : List.of(list("safety","safe"),list("convention","prim"),list("extra",0L))) mutate.call((c,d) -> d.put((String) edit.get(0),edit.get(1)));
            for (var edit : List.of(list("unit","base"),list("isFunction",false),list("kind","dynamic"),list("extra",1L))) mutate.call((c,d) -> ((Map<String,Object>) d.get("target")).put((String) edit.get(0),edit.get(1)));
            for (int i = 0; i < ((List<?>) original.get(2)).size(); i++) { int index = i; mutate.call((c,d) -> ((List<Object>) c.get(3)).set(index,true));
                mutate.call((c,d) -> ((List<Map<String,Object>>) d.get("argumentReps")).get(index).put("primReps",list("WordRep"))); mutate.call((c,d) -> ((List<Map<String,Object>>) d.get("argumentReps")).get(index).put("aggregate","unboxed-tuple")); }
            for (var site : List.of("resultRep","rep")) mutate.call((c,d) -> ((Map<String,Object>) (site.equals("rep") ? (Map<?,?>) c.get(6) : d).get(site)).put("primReps",list("WordRep")));
            var aliased = (List<Object>) copy(original); var descriptor = (Map<String,Object>) ((Map<?,?>) aliased.get(6)).get("foreignCall"); var target = (Map<String,Object>) descriptor.get("target"); target.put("symbol","prefix" + target.get("symbol")); assertNull(validate(aliased)); target.put("symbol","__hscore_fstat"); assertThrows(RuntimeFault.class,() -> validate(aliased));
        }
        assertTrue(Files.readString(new File(root,"scripts/core-capabilities.json").toPath()).contains("\"__hscore_fstat\""));
    }
}
