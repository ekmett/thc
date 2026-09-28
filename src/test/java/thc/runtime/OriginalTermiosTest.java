// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.io.IOAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.*;
import thc.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.function.BiConsumer;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.ScalarTestCalls.callScalarTestTarget;
import static thc.runtime.OriginalStdioChecks.*;

@EnabledOnOs(OS.LINUX)
@EnabledIfSystemProperty(named = "os.arch",matches = "amd64|x86_64")
@SuppressWarnings("unchecked")
class OriginalTermiosTest {
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final String prefix = "build/original-termios";
    private final List<String> names = List.of("originalTermiosSize","originalEcho","originalIcanon","originalVmin","originalVtime","originalTcsanow","originalSigsetSize","originalSigttou","originalSigBlock","originalSigSetmask","originalLflag","originalPokeLflag","originalCC");
    private final int constantCount = 10;
    private final List<String> symbols = List.of("__hscore_sizeof_termios","__hscore_echo","__hscore_icanon","__hscore_vmin","__hscore_vtime","__hscore_tcsanow","__hscore_sizeof_sigset_t","__hscore_sigttou","__hscore_sig_block","__hscore_sig_setmask","__hscore_lflag","__hscore_poke_lflag","__hscore_ptr_c_cc");
    private Map<String,Object> json(String path) throws Exception { return (Map<String,Object>) Json.parse(Files.readString(new File(root,path).toPath())); }
    private Map<String,Object> module(String stage) throws Exception { var modules = new ArrayList<Map<String,Object>>(); for (var part : List.of("OriginalTermiosAudit","THC.InterfaceClosure")) modules.add(json(prefix + "/" + stage + "/core/" + part + ".json")); return CoreModules.merge(modules); }
    private Object copy(Object value) { return Json.parse(Json.stringify(value)); }
    private Context context() { return Context.newBuilder("thc").allowIO(IOAccess.NONE).allowExperimentalOptions(true).option("engine.BackgroundCompilation","false").option("engine.MultiTier","false").option("engine.CompilationFailureAction","Throw").build(); }
    private void valid(RootCallTarget target) throws Exception { assertEquals(true,target.getClass().getMethod("isValidLastTier").invoke(target)); }
    private OriginalStdioOp validate(List<Object> call) { var reps = new ArrayList<Object>(); for (var arg : (List<List<Object>>) call.get(2)) { var metadata = CoreRepresentations.metadata(arg); reps.add(metadata == null ? null : metadata.get("rep")); } return CoreOriginalStdio.validate(call.get(6),reps,(List<?>) call.get(3),((Map<?,?>) call.get(6)).get("rep")); }
    private List<List<Object>> calls() throws Exception { var calls = new ArrayList<List<Object>>(); var seen = new HashSet<Object>(); for (var call : foreignCalls(module("pre"))) if (seen.add(((Map<?,?>) ((Map<?,?>) call.get(6)).get("foreignCall")).get("target"))) calls.add(call); return calls; }
    private static Object[] packet(Object[] arguments,Object first) { Object[] values = new Object[arguments.length + 1]; values[0] = first; System.arraycopy(arguments,0,values,1,arguments.length); return values; }
    @Test void genuineNativeImagesAndPointersMatchBothBackendsAtFirstInstalledEntry() throws Exception {
        var manifest = json(prefix + "/manifest.json"); assertEquals(1L,manifest.get("schema")); assertEquals("linux",manifest.get("platform")); assertEquals(true,manifest.get("supported")); assertEquals(names,manifest.get("entries")); assertEquals(true,manifest.get("strictAccepted")); assertEquals(false,manifest.get("runtimeVerified")); assertEquals(false,manifest.get("installedArtifactsHashed")); assertEquals(6L,manifest.get("nativeRows"));
        hashes(root,manifest.get("inputHashes"),Set.of("t/fixtures/compiler/OriginalTermiosAudit.hs","t/fixtures/compiler/OriginalTermiosNative.hs","t/haskell-fixtures/OriginalTermiosFixtures.hs","t/haskell-fixtures/FixtureSupport.hs","t/haskell-fixtures/Main.hs","thc.cabal","bin/audit-core.py","bin/core_original_foreign.py","bin/core-capabilities.json"));
        var artifacts = new HashSet<>(Set.of(prefix + "/oracle.json",prefix + "/native/oracle")); for (var stage : List.of("pre","post")) { for (var name : names) artifacts.add(prefix + "/" + stage + "/" + name + ".audit.json"); for (var part : List.of("OriginalTermiosAudit","THC.InterfaceClosure")) artifacts.add(prefix + "/" + stage + "/core/" + part + ".json"); } hashes(root,manifest.get("artifactHashes"),artifacts,prefix + "/");
        var oracle = json(prefix + "/oracle.json"); var constants = (List<Long>) oracle.get("constants"); var rows = (List<List<Object>>) oracle.get("rows"); Map<?,?> probe;
        try (var input = TermiosImage.class.getResourceAsStream("/thc/native/termios-abi.json")) { probe = (Map<?,?>) ((Map<?,?>) Json.parse(new String(Objects.requireNonNull(input).readAllBytes(),StandardCharsets.UTF_8))).get("termios"); }
        var expectedConstants = new ArrayList<Object>(); for (var key : List.of("size","echo","icanon","vmin","vtime","tcsanow","sigsetSize","sigttou","sigBlock","sigSetmask")) expectedConstants.add(probe.get(key)); assertEquals(expectedConstants,constants);
        var values = new ArrayList<Object>(); for (var row : rows) values.add(row.get(0)); assertEquals(List.of(0L,1L,255L,0x80000000L,0xffffffffL,0xdeadbeefL),values);
        for (var row : rows) { assertEquals(0L,row.get(1)); assertEquals(row.get(0),row.get(2)); assertEquals(probe.get("ccOffset"),row.get(3)); var image = (List<Long>) row.get(4); assertEquals(constants.get(0).intValue() + 16,image.size());
            for (int i = 0; i < 8; i++) { assertEquals(90L,image.get(i)); assertEquals(90L,image.get(image.size() - 8 + i)); }
            assertEquals(171L,image.get(8 + ((Long) row.get(3)).intValue() + constants.get(3).intValue())); assertEquals(205L,image.get(8 + ((Long) row.get(3)).intValue() + constants.get(4).intValue())); }
        for (var stage : List.of("pre","post")) {
            var source = module(stage); var actualSymbols = new HashSet<String>(); for (var call : foreignCalls(source)) actualSymbols.add(Objects.requireNonNull(validate(call)).getSymbol()); assertEquals(new HashSet<>(symbols),actualSymbols);
            for (var name : names) { var audit = json(prefix + "/" + stage + "/" + name + ".audit.json"); assertEquals(true,audit.get("accepted"),stage + "/" + name); assertEquals(List.of(),audit.get("issues")); assertEquals(List.of(),audit.get("missingGlobals"));
                var audited = new ArrayList<Object>(); for (var call : (List<Map<?,?>>) audit.get("foreignCalls")) audited.add(call.get("symbol")); assertEquals(List.of(symbols.get(names.indexOf(name))),audited); }
            for (var backend : List.of("ast","bytecode")) try (var context = context()) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    for (int at = 0; at < names.size(); at++) {
                        int index = at; var name = names.get(index); var linked = with(CoreModules.reachable(source,name),"instrument",true); ExecutableProgram program = backend.equals("ast") ? new Program(language,linked) : new BytecodeProgram(language,linked); var entry = program.entryTarget(name);
                        int executedRoots;
                        if (index < constantCount) executedRoots = 1;
                        else {
                            var evidence = new ArrayCoreEvidence(source,name); var outer = (List<Object>) evidence.getRoot().get("expr"); assertEquals(1,evidence.getBindings().size()); assertTrue(evidence.globalReferences(outer).isEmpty()); assertEquals("lam",outer.get(0));
                            var reps = new ArrayList<Object>(); for (var formal : (List<Map<String,Object>>) outer.get(1)) reps.add(single((List<?>) ((Map<?,?>) formal.get("rep")).get("primReps"),ignored -> true)); assertEquals(name.equals("originalPokeLflag") ? List.of("AddrRep","WordRep") : List.of("AddrRep"),reps);
                            var state = evidence.immediateStateLambda(outer.get(2)); assertEquals(list(outer,state),evidence.guestLambdas(outer)); var lowered = evidence.loweredGuestLambdas(outer); assertEquals(list(outer),lowered,name + ": the State# redex executes in-frame"); executedRoots = lowered.size();
                        }
                        var installed = Collections.newSetFromMap(new IdentityHashMap<RootCallTarget,Boolean>());
                        class Exercise {
                            List<RootCallTarget> active = List.of();
                            void install(RootCallTarget target) throws Exception { assertTrue(installed.add(target),"compile each target only once"); target.getClass().getMethod("compile",boolean.class).invoke(target,true); valid(target); }
                            void released() { var handoff = language.getHandoffState().get(); assertEquals(0,handoff.getArguments().getDepth()); assertEquals(0,handoff.getResults().getDepth()); assertEquals(0,handoff.getArguments().retainedReferences()); assertEquals(0,handoff.getResults().retainedReferences()); }
                            Object invoke(Object[] arguments,boolean compiled) throws Exception {
                                long before = ((Number) program.diagnostics().get("compiledEntries")).longValue(); var result = Calls.target(entry,packet(arguments,0L));
                                if (compiled) { assertEquals(before + executedRoots,((Number) program.diagnostics().get("compiledEntries")).longValue(),stage + "/" + backend + "/" + name); assertEquals(active,targets(entry)); for (var target : active) valid(target); } released(); return result;
                            }
                            void run(boolean compiled) throws Exception {
                                if (index < constantCount) for (long extra : new long[]{-7,0,13}) assertEquals(constants.get(index) + extra,invoke(new Object[]{extra},compiled));
                                else for (var row : rows) {
                                    byte[] bytes = new byte[constants.get(0).intValue() + 16]; Arrays.fill(bytes,(byte) 90); var address = ManagedAddress.fromByteArray(bytes).plus(8); var image = (List<Long>) row.get(4); byte[] expected = new byte[image.size()]; for (int i = 0; i < expected.length; i++) expected[i] = image.get(i).byteValue();
                                    if (name.equals("originalPokeLflag")) { assertEquals(0L,invoke(new Object[]{address,row.get(0)},compiled)); int ccOffset = ((Long) row.get(3)).intValue(); bytes[8 + ccOffset + constants.get(3).intValue()] = (byte) 171; bytes[8 + ccOffset + constants.get(4).intValue()] = (byte) 205; assertArrayEquals(expected,bytes); }
                                    else { System.arraycopy(expected,0,bytes,0,expected.length); if (name.equals("originalLflag")) assertEquals(row.get(2),invoke(new Object[]{address},compiled));
                                        else { var cc = (ManagedAddress) invoke(new Object[]{address},compiled); cc.writeWord8(constants.get(3),37L); assertEquals((byte) 37,bytes[8 + ((Long) row.get(3)).intValue() + constants.get(3).intValue()]); } }
                                }
                            }
                        }
                        var exercise = new Exercise(); var bindings = (List<Map<String,Object>>) linked.get("bindings"); var binding = single(bindings,b -> Objects.equals(b.get("name"),name)); boolean directConstant = name.equals("originalSigsetSize");
                        if (directConstant) {
                            // Require the installed Int helper inlined directly into its consumer.
                            assertEquals(1,bindings.size()); assertEquals(1L,binding.get("arity")); assertSame(entry,program.entryTarget((String) binding.get("id"))); assertEquals(CoreFunctionIdentity.from(linked,binding),((GuestRoot) entry.getRootNode()).getCoreIdentity());
                            var call = single(foreignCalls(binding.get("expr")),ignored -> true); assertEquals(OriginalStdioOp.SIZEOF_SIGSET,validate(call)); assertEquals(List.of(entry),targets(entry));
                        } else if (index < constantCount) {
                            // Calling the original CAF target leaves its shared Thunk unforced.
                            assertEquals(2,bindings.size()); var caf = single(bindings,b -> !Objects.equals(b.get("id"),binding.get("id"))); assertEquals(0L,caf.get("arity")); assertEquals(true,caf.get("lifted")); assertEquals("Int",caf.get("type"));
                            assertEquals(map("primReps",list("BoxedRep (Just Lifted)"),"kind","data","evaluated",false),caf.get("rep")); var cafSymbols = new ArrayList<String>(); for (var call : foreignCalls(caf.get("expr"))) cafSymbols.add(Objects.requireNonNull(validate(call)).getSymbol()); assertEquals(List.of(symbols.get(index)),cafSymbols);
                            var cafId = (String) caf.get("id"); var thunk = (Thunk) program.entryValue(cafId); var target = program.entryTarget(cafId); assertSame(target,thunk.getTarget()); assertEquals(CoreFunctionIdentity.from(linked,caf),((GuestRoot) target.getRootNode()).getCoreIdentity()); assertNull(thunk.getEnvironment());
                            class Caf { void invoke(boolean compiled) throws Exception {
                                assertEquals(0,thunk.getState()); assertSame(target,thunk.getTarget()); assertSame(thunk,program.entryValue(cafId)); assertSame(target,program.entryTarget(cafId)); long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                                var value = (DataValue) Calls.target(target,new Object[]{0L}); assertEquals(DataValues.BOXED_INT_CONSTRUCTOR_ID,value.getLayout().getId()); assertEquals(1,value.getLayout().getArity()); assertEquals(constants.get(index),value.getLayout().readLong(value,0));
                                assertEquals(0,thunk.getState()); assertSame(target,thunk.getTarget()); assertEquals(List.of(target),targets(target)); if (compiled) { assertEquals(before + 1,((Number) program.diagnostics().get("compiledEntries")).longValue()); valid(target); } exercise.released();
                            }}
                            var cafCall = new Caf(); for (int i = 0; i < 3; i++) cafCall.invoke(false); exercise.install(target); for (int i = 0; i < 3; i++) cafCall.invoke(true);
                        }
                        exercise.run(false); exercise.active = targets(entry); int lambdas = 0; for (var node : nodes(binding.get("expr"))) if (!node.isEmpty() && Objects.equals(node.getFirst(),"lam")) lambdas++; assertEquals(index < constantCount ? 1 : 2,lambdas);
                        int expectedTargets = index < constantCount && !directConstant ? 2 : executedRoots; assertEquals(expectedTargets,exercise.active.size(),stage + "/" + backend + "/" + name); for (var target : exercise.active) if (!installed.contains(target)) exercise.install(target); else valid(target); exercise.run(true);
                    }
                } finally { context.leave(); }
            }
        }
    }
    @Test void stateStoredOperandsAndAllDestinationPreflightsFailBeforeMutation() throws Exception {
        var source = module("pre");
        for (var backend : List.of("ast","bytecode")) try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                class Load { ExecutableProgram call(Map<String,Object> raw) { return backend.equals("ast") ? new Program(language,raw) : new BytecodeProgram(language,raw); }}
                var load = new Load();
                for (var call : calls()) {
                    var operation = Objects.requireNonNull(validate(call)); var raw = rawModule(call,source); var target = load.call(raw).entryTarget("entry"); byte[] bytes = new byte[4096]; Arrays.fill(bytes,(byte) 90); var address = ManagedAddress.fromByteArray(bytes); var args = new ArrayList<Object>();
                    for (var rep : operation.getArguments().subList(0,operation.getArguments().size() - 1)) args.add(Objects.equals(rep,"AddrRep") ? address : Objects.equals(rep,"Word32Rep") || Objects.equals(rep,"Int32Rep") ? (Object) 7 : 7L);
                    var invalid = new ArrayList<>(args); invalid.add(9L); assertThrows(RuntimeFault.class,() -> Calls.target(target,packet(invalid.toArray(),0L))); var error = assertThrows(RuntimeFault.class,() -> callScalarTestTarget(target,packet(invalid.toArray(),0L))); assertTrue(Objects.toString(error.getMessage(),"").contains("zero-width scalar carrier"),error.getMessage()); for (byte value : bytes) assertEquals((byte) 90,value);
                    for (int i = 0; i < operation.getArguments().size(); i++) { int index = i; assertThrows(RuntimeFault.class,() -> load.call(rawModule(call,source,index))); }
                    if (operation.getTermiosAddress()) for (var bad : List.of(ManagedAddress.nullAddress(),address.plus(4095))) { var badArgs = new ArrayList<>(args); badArgs.set(0,bad); badArgs.add(thc.runtime.Unit.INSTANCE); assertThrows(RuntimeFault.class,() -> callScalarTestTarget(target,packet(badArgs.toArray(),0L))); }
                    if (operation == OriginalStdioOp.POKE_LFLAG) for (long bad : new long[]{-1,1L << 32}) { assertThrows(RuntimeFault.class,() -> callScalarTestTarget(target,new Object[]{0L,address,bad,thc.runtime.Unit.INSTANCE})); for (byte value : bytes) assertEquals((byte) 90,value); }
                    var malformed = (Map<String,Object>) copy(raw); ((List<Object>) single(foreignCalls(malformed),ignored -> true).get(1)).set(1,17L); assertThrows(RuntimeFault.class,() -> load.call(malformed)); var handoff = language.getHandoffState().get(); assertEquals(0,handoff.getArguments().getDepth()); assertEquals(0,handoff.getResults().getDepth());
                }
            } finally { context.leave(); }
        }
    }
    @Test void exactOriginalDescriptorsRejectMalformedStateOnlyAndOtherRawProofs() throws Exception {
        var calls = calls(); assertEquals(13,calls.size()); var actual = new HashSet<String>(); for (var call : calls) actual.add(Objects.requireNonNull(validate(call)).getSymbol()); assertEquals(new HashSet<>(symbols),actual);
        for (var original : calls) {
            var operation = Objects.requireNonNull(validate(original));
            var mutate = new Object() { void call(BiConsumer<List<Object>,Map<String,Object>> action) { var call = (List<Object>) copy(original); var descriptor = (Map<String,Object>) ((Map<?,?>) call.get(6)).get("foreignCall"); action.accept(call,descriptor); assertThrows(RuntimeFault.class,() -> validate(call)); }};
            if (operation.getResult() != null) for (var wrong : List.of("IntRep","Int32Rep","Word32Rep")) {
                if (wrong.equals(operation.getResult())) continue;
                mutate.call((c,d) -> { var metadata = (Map<?,?>) c.get(6); for (var result : list(metadata.get("rep"),d.get("resultRep"))) { var tuple = (Map<String,Object>) result; tuple.put("primReps",list(wrong)); var scalar = ((List<Map<String,Object>>) tuple.get("components")).get(1); scalar.put("kind","long"); scalar.put("primReps",list(wrong)); } });
            }
            for (var key : List.of("schema","arity","suppliedArity")) for (var bad : list(null,true,1.0,"1",-1L,1L << 32)) mutate.call((c,d) -> d.put(key,bad));
            for (var edit : List.of(list("safety","safe"),list("safety","interruptible"),list("convention","capi"),list("extra",0L))) mutate.call((c,d) -> d.put((String) edit.get(0),edit.get(1)));
            for (var edit : List.of(list("unit","base"),list("kind","dynamic"),list("isFunction",false),list("extra",1L))) mutate.call((c,d) -> ((Map<String,Object>) d.get("target")).put((String) edit.get(0),edit.get(1)));
            for (int i = 0; i < ((List<?>) original.get(2)).size(); i++) { int index = i; mutate.call((c,d) -> ((List<Object>) c.get(3)).set(index,true)); mutate.call((c,d) -> ((List<Map<String,Object>>) d.get("argumentReps")).get(index).put("primReps",list("WordRep"))); mutate.call((c,d) -> ((List<Map<String,Object>>) d.get("argumentReps")).get(index).put("aggregate","unboxed-tuple")); }
            for (var site : List.of("resultRep","rep")) {
                mutate.call((c,d) -> (site.equals("rep") ? (Map<String,Object>) c.get(6) : d).put(site,OriginalStdioFixtures.scalar(null)));
                mutate.call((c,d) -> ((Map<String,Object>) (site.equals("rep") ? (Map<?,?>) c.get(6) : d).get(site)).put("components",List.of()));
            }
            var alias = (List<Object>) copy(original); var target = (Map<String,Object>) ((Map<?,?>) ((Map<?,?>) alias.get(6)).get("foreignCall")).get("target"); target.put("symbol","prefix" + target.get("symbol")); assertNull(validate(alias));
        }
    }
}
