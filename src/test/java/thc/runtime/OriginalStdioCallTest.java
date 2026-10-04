// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.bytecode.BytecodeConfig;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import thc.Language;
import thc.ContextProfile;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.OriginalStdioChecks.*;
import static thc.runtime.ScalarTestCalls.callScalarTestTarget;

/** Synthetic lowering controls; authentic imported consumers are tested separately. */
@SuppressWarnings("unchecked")
class OriginalStdioCallTest {
    @TempDir Path directory;
    /** Check the actual JVM carrier at the shared instruction's destination. */
    @Test @EnabledOnOs(OS.LINUX)
    void sharedTransferInstructionKeepsDeclaredResultWidthsAndStateOrder() throws Exception {
        try (var context = NativeFileProvider.createContext(Set.of())) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var state = Language.currentState();
                var path = directory.resolve("input"); Files.write(path,new byte[]{41}); var address = ManagedAddress.fromByteArray((path + "\0").getBytes(StandardCharsets.UTF_8));
                for (var operation : List.of(OriginalStdioOp.OPEN,OriginalStdioOp.OPEN_SAFE,OriginalStdioOp.OPEN_INTERRUPTIBLE,OriginalStdioOp.TCSETATTR,OriginalStdioOp.READ_SAFE,OriginalStdioOp.READ_UNSAFE,OriginalStdioOp.WRITE_SAFE,OriginalStdioOp.WRITE_UNSAFE)) {
                    boolean opening = List.of(OriginalStdioOp.OPEN,OriginalStdioOp.OPEN_SAFE,OriginalStdioOp.OPEN_INTERRUPTIBLE).contains(operation);
                    var input = operation == OriginalStdioOp.TCSETATTR ? ManagedAddress.fromByteArray(new byte[(int) TermiosImage.scalar(OriginalStdioOp.SIZEOF_TERMIOS,ManagedAddress.nullAddress(),0)]) : address;
                    var target = BytecodeRootGen.create(language,BytecodeConfig.DEFAULT,b -> {
                        b.beginRoot(); var result = b.createLocal("declared foreign result","primitive"); b.beginOriginalStdioTransfer(result,operation);
                        b.emitLoadConstant(opening ? 0L : -1L); b.emitLoadConstant(input); b.emitLoadConstant(0L); b.emitLoadArgument(1); b.endOriginalStdioTransfer();
                        b.beginReturn(); b.emitLoadLocal(result); b.endReturn(); b.endRoot();
                    }).getNode(0).getCallTarget();
                    assertThrows(RuntimeFault.class,() -> Calls.target(target,new Object[]{0L,17L})); assertEquals(3L,state.getFiles().duplicate(1),"Bad State must not acquire an open descriptor"); assertEquals(0L,state.getFiles().close(3));
                    var result = Calls.target(target,new Object[]{0L,thc.runtime.Unit.INSTANCE});
                    if (opening || operation == OriginalStdioOp.TCSETATTR) { assertInstanceOf(Integer.class,result,operation.name()); assertEquals(opening ? 3 : -1,result); }
                    else { assertInstanceOf(Long.class,result,operation.name()); assertEquals(-1L,result); }
                    if (opening) assertEquals(0L,state.getFiles().close(3));
                }
            } finally { context.leave(); }
        }
    }
    private Context context(ByteArrayOutputStream out,ByteArrayOutputStream err) { return Context.newBuilder("thc").out(out).err(err).allowExperimentalOptions(true).option("engine.BackgroundCompilation","false").option("engine.MultiTier","false").option("engine.CompilationFailureAction","Throw").build(); }
    private void valid(RootCallTarget target) throws Exception { assertEquals(true,target.getClass().getMethod("isValidLastTier").invoke(target)); }
    private void released(Language language) { var state = language.getHandoffState().get(); assertEquals(0,state.getArguments().getDepth()); assertEquals(0,state.getResults().getDepth()); assertEquals(0,state.getArguments().retainedReferences()); assertEquals(0,state.getResults().retainedReferences()); }
    // Synthetic Word32 Core route on both POSIX hosts, independent of authentic GHC wrapper widths.
    @Test @EnabledOnOs({OS.LINUX, OS.MAC})
    void coreOpenPreservesUnsignedModeAndStateBeforeEffectsInFirstInstalledCode() throws Exception {
        for (var backend : List.of("ast", "bytecode"))
            try (var context = NativeFileProvider.createContext(Set.of(), ContextProfile.SYNCHRONOUS_TEST)) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    var module = OriginalStdioFixtures.module(List.of("open"));
                    ExecutableProgram program = backend.equals("ast") ? new Program(language, module) : new BytecodeProgram(language, module);
                    var entry = program.entryTarget("open"); var stdio = Language.currentState().getStdio();
                    int flags = (int) (stdio.flagConstant(OriginalStdioOp.O_WRONLY) | stdio.flagConstant(OriginalStdioOp.O_CREAT)
                        | stdio.flagConstant(OriginalStdioOp.O_EXCL));
                    // Word32# uses a negative JVM int; signed widening would reject this mode before creation.
                    int mode = 0x8000_0180;
                    var observedDirectory = Files.createDirectories(directory.resolve(backend).resolve("native/child")).getParent();
                    Files.createSymbolicLink(observedDirectory.getParent().resolve("link"), observedDirectory.resolve("child"));
                    // Kernel link/.. resolution must reach native/, rather than a lexically normalized parent.
                    var prefix = (observedDirectory.getParent() + "/link/../n").getBytes(StandardCharsets.UTF_8);
                    for (boolean compiled : new boolean[] {false, true}) {
                        var terminated = Arrays.copyOf(prefix, prefix.length + 3);
                        // Darwin filesystems reject invalid UTF-8 names; Linux also checks their raw identity.
                        int suffix = "Linux".equals(System.getProperty("os.name")) ? 0xfe : 'a';
                        terminated[prefix.length] = (byte) (suffix + (compiled ? 1 : 0)); terminated[terminated.length - 1] = -1;
                        // NUL must hide the trailing invalid byte on both platforms.
                        var address = ManagedAddress.fromByteArray(terminated);
                        var active = targets(entry);
                        if (compiled) for (var target : active) {
                            target.getClass().getMethod("compile", boolean.class).invoke(target, true); valid(target);
                        }
                        long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                        var result = callScalarTestTarget(entry, new Object[] {0L, address, flags, mode, thc.runtime.Unit.INSTANCE});
                        int fd = assertInstanceOf(Integer.class, result); assertTrue(fd >= 0, "Core open must create the requested file");
                        try {
                            if (compiled) {
                                assertTrue(((Number) program.diagnostics().get("compiledEntries")).longValue() > before,
                                    "The first call after compilation must execute installed code");
                                for (var target : active) valid(target);
                            }
                            byte[] content = {37, (byte) (compiled ? 2 : 1)};
                            assertEquals(content.length, stdio.write(fd, ManagedAddress.fromByteArray(content), content.length));
                        } finally { assertEquals(0L, stdio.close(fd)); }
                        released(language);
                    }
                    // Unix directory-stream Paths retain raw bytes; never reconstruct these names from String.
                    List<Path> names;
                    try (var entries = Files.list(observedDirectory)) { names = entries.filter(Files::isRegularFile).toList(); }
                    assertEquals(2, names.size()); assertNotEquals(names.get(0), names.get(1));
                    var contents = new HashSet<String>(); var rawNames = new HashSet<String>();
                    for (var name : names) {
                        contents.add(hex(Files.readAllBytes(name)));
                        String raw = name.toUri().getRawPath(); rawNames.add(raw.substring(raw.lastIndexOf('/') + 1));
                    }
                    assertEquals("Linux".equals(System.getProperty("os.name")) ? Set.of("n%FE", "n%FF") : Set.of("na", "nb"), rawNames);
                    assertEquals(Set.of("2501", "2502"), contents, "Both raw names must denote independent native files");
                    var absent = directory.resolve(backend + "-bad-state");
                    var address = ManagedAddress.fromByteArray((absent + "\0").getBytes(StandardCharsets.UTF_8));
                    assertEquals(-1L, stdio.close(-1)); long errno = stdio.errno();
                    assertThrows(RuntimeFault.class, () -> callScalarTestTarget(entry, new Object[] {0L, address, flags, mode, 9L}));
                    assertFalse(Files.exists(absent), "Malformed State must fail before native creation");
                    assertEquals(errno, stdio.errno(), "Malformed State must not change errno"); released(language);
                } finally { context.leave(); }
            }
    }
    @Test void duplicateOperationsExecuteInFirstInstalledCodeAndValidateStateBeforeEffects() throws Exception {
        for (var backend : List.of("ast","bytecode")) try (var context = context(new ByteArrayOutputStream(),new ByteArrayOutputStream())) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var module = OriginalStdioFixtures.module(List.of("dup","dup2"));
                ExecutableProgram program = backend.equals("ast") ? new Program(language,module) : new BytecodeProgram(language,module); var targets = new LinkedHashMap<String,RootCallTarget>(); for (var name : List.of("dup","dup2")) targets.put(name,program.entryTarget(name));
                var state = Language.currentState(); var files = state.getFiles();
                class Exercise {
                    boolean compiled;
                    long call(String name,long... fds) throws Exception {
                        long before = ((Number) program.diagnostics().get("compiledEntries")).longValue(); Object[] args = new Object[fds.length + 2]; args[0] = 0L;
                        for (int i = 0; i < fds.length; i++) args[i + 1] = (int) fds[i]; args[args.length - 1] = thc.runtime.Unit.INSTANCE;
                        long result = (Integer) callScalarTestTarget(targets.get(name),args);
                        if (compiled) { assertTrue(((Number) program.diagnostics().get("compiledEntries")).longValue() > before,name + " must execute installed code"); for (var target : targets.values()) valid(target); }
                        released(language); return result;
                    }
                    void run() throws Exception {
                        assertEquals(-1L,state.getStdio().close(-1)); assertEquals(3L,call("dup",1)); assertEquals(3L,call("dup2",1,3)); assertEquals(3L,call("dup2",3,3)); assertEquals(71L,call("dup2",3,71));
                        assertEquals(0L,files.close(3)); assertEquals(3L,call("dup",71)); assertEquals(-1L,call("dup",-1)); assertEquals(-1L,call("dup2",-1,1)); assertEquals(-1L,call("dup2",1,-1));
                        assertEquals(StdioHostAbi.load().error(4),state.getStdio().errno()); assertEquals(0L,files.close(3)); assertEquals(0L,files.close(71));
                    }
                }
                var exercise = new Exercise(); exercise.run(); for (var target : targets.values()) { target.getClass().getMethod("compile",boolean.class).invoke(target,true); valid(target); } exercise.compiled = true; exercise.run();
                var invalid = new LinkedHashMap<String,Object[]>(); invalid.put("dup",new Object[]{0L,1,9L}); invalid.put("dup2",new Object[]{0L,1,0,9L});
                for (var entry : invalid.entrySet()) { assertThrows(RuntimeFault.class,() -> callScalarTestTarget(targets.get(entry.getKey()),entry.getValue()));
                    assertEquals(3L,files.duplicate(1)); assertEquals(0L,files.close(3)); assertEquals(-1L,files.write(0,ManagedAddress.fromByteArray(new byte[0]),0,ForeignSafety.UNSAFE),"Malformed State cannot replace stdin"); }
                assertEquals(0L,((Number) program.diagnostics().get("unsupportedTraps")).longValue()); released(language);
            } finally { context.leave(); }
        }
    }
    @Test void transfersPreserveBytesErrnoAndStateOnEveryFirstCompiledCall() throws Exception {
        exerciseTransfers();
    }
    private void exerciseTransfers() throws Exception {
        for (var backend : List.of("ast","bytecode")) {
            boolean[] safeTransfer = {false};
            var out = new ByteArrayOutputStream() {
                @Override public synchronized void write(byte[] bytes, int offset, int count) {
                    var threads = Language.currentState().getThreads();
                    if (safeTransfer[0]) {
                        threads.enterCurrent(null, false, true, null);
                        threads.leaveCurrent(GuestThreadStatus.FINISHED);
                    } else assertThrows(RuntimeFault.class, () -> threads.enterCurrent(null, false, true, null));
                    super.write(bytes, offset, count);
                }
            };
            var err = new ByteArrayOutputStream();
            try (var context = context(out,err)) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    var module = OriginalStdioFixtures.module(List.of("safe_write","unsafe_write","errno"), call -> ((List<Object>) call.get(1)).set(1,"unrelated-package:InlineCaller.arbitrary"));
                    ExecutableProgram program = backend.equals("ast") ? new Program(language,module) : new BytecodeProgram(language,module);
                    var targets = new LinkedHashMap<String,RootCallTarget>(); for (var name : List.of("safe_write","unsafe_write","errno")) targets.put(name,program.entryTarget(name));
                    long ebadf = StdioHostAbi.load().error(4L);
                    class Exercise {
                        boolean compiled;
                        Object call(String name,Object... args) throws Exception {
                            safeTransfer[0] = name.equals("safe_write");
                            long before = ((Number) program.diagnostics().get("compiledEntries")).longValue(); var target = targets.get(name); Object[] guest = new Object[args.length + 2]; guest[0] = 0L;
                            for (int i = 0; i < args.length; i++) { var value = args[i]; guest[i + 1] = Objects.equals(OriginalStdioFixtures.signatures.get(name).get(i),"Int32Rep") && value instanceof Long word && word >= Integer.MIN_VALUE && word <= Integer.MAX_VALUE ? Integer.valueOf(((Long) value).intValue()) : value; }
                            guest[guest.length - 1] = thc.runtime.Unit.INSTANCE; var raw = callScalarTestTarget(target,guest); Object result = Objects.equals(OriginalStdioFixtures.output(name),"Int32Rep") ? Long.valueOf(((Integer) raw).longValue()) : raw;
                            if (compiled) { assertTrue(((Number) program.diagnostics().get("compiledEntries")).longValue() > before,name + " must execute installed code"); valid(target); } released(language); return result;
                        }
                        void run(int pass) throws Exception {
                            for (var name : List.of("safe_write","unsafe_write")) {
                                byte[] bytes = {0x55,(byte) pass,0,-1,10,0x66}; var address = ManagedAddress.fromByteArray(bytes).plus(1L); int beforeOut = out.size(), beforeErr = err.size();
                                assertEquals(4L,call(name,1L,address,4L)); assertArrayEquals(Arrays.copyOfRange(bytes,1,5),Arrays.copyOfRange(out.toByteArray(),beforeOut,out.size()));
                                assertEquals(4L,call(name,2L,address,4L)); assertArrayEquals(Arrays.copyOfRange(bytes,1,5),Arrays.copyOfRange(err.toByteArray(),beforeErr,err.size()));
                                assertEquals(0L,call(name,1L,address,0L)); assertEquals(-1L,call(name,-1L,address,4L)); assertEquals(ebadf,call("errno"));
                                assertEquals(1L,call(name,1L,address,1L)); assertEquals(ebadf,call("errno"));
                            }
                        }
                    }
                    var exercise = new Exercise(); assertEquals(0L,exercise.call("errno")); exercise.run(1);
                    for (var target : targets.values()) { target.getClass().getMethod("compile",boolean.class).invoke(target,true); valid(target); }
                    exercise.compiled = true; exercise.run(2); // No settling call or recompile.
                    exercise.compiled = false;
                    for (var name : List.of("safe_write","unsafe_write")) {
                        var address = ManagedAddress.fromByteArray(new byte[]{3,4}); var before = out.toByteArray();
                        assertThrows(RuntimeFault.class,() -> callScalarTestTarget(targets.get(name),new Object[]{0L,1,address,2L,9L})); assertArrayEquals(before,out.toByteArray()); assertEquals(ebadf,exercise.call("errno"));
                        assertThrows(RuntimeFault.class,() -> exercise.call(name,1L,address,-1L)); assertThrows(RuntimeFault.class,() -> exercise.call(name,1L,address,3L)); assertThrows(RuntimeFault.class,() -> exercise.call(name,1L << 32,address,2L));
                        assertArrayEquals(before,out.toByteArray()); assertEquals(ebadf,exercise.call("errno")); released(language);
                    }
                    assertThrows(RuntimeFault.class,() -> callScalarTestTarget(targets.get("errno"),new Object[]{0L,9L})); released(language);
                } finally { context.leave(); }
            }
        }
    }
    private Map<String,Object> descriptor(List<Object> call) { return (Map<String,Object>) ((Map<?,?>) call.get(6)).get("foreignCall"); }
    @Test void bothLoadersRejectBoundHeadsWrongProvenanceAndMalformedContracts() {
        var out = new ByteArrayOutputStream(); var err = new ByteArrayOutputStream();
        for (var backend : List.of("ast","bytecode")) try (var context = context(out,err)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                class Load { ExecutableProgram call(String name,Consumer<List<Object>> mutate) {
                    var module = OriginalStdioFixtures.module(List.of(name),mutate); return backend.equals("ast") ? new Program(language,module) : new BytecodeProgram(language,module);
                }
                    void unknown(String name, String field, Object value) {
                        String symbol = field.equals("symbol") ? (String) value : OriginalStdioFixtures.symbols.get(name);
                        var program = call(name, it -> ((Map<String,Object>) descriptor(it).get("target")).put(field, value));
                        var reps = OriginalStdioFixtures.signatures.get(name);
                        var args = new Object[reps.size() + 1]; args[0] = 0L;
                        for (int i = 0; i < reps.size(); i++) args[i + 1] = switch (reps.get(i)) {
                            case null -> Unit.INSTANCE; case "AddrRep" -> ManagedAddress.nullAddress();
                            case "Int32Rep" -> -1; default -> 0L;
                        };
                        // Only unknown calls trap when reached; known malformed ABIs above remain eager.
                        assertEquals(0L, ((Number) program.diagnostics().get("unsupportedTraps")).longValue());
                        var error = assertThrows(UnsupportedCore.class,
                            () -> callScalarTestTarget(program.entryTarget(name), args), backend + "/" + symbol);
                        assertEquals("Unsupported foreign call: " + symbol, error.getMessage());
                        assertEquals(1L, ((Number) program.diagnostics().get("unsupportedTraps")).longValue());
                        assertEquals(0, out.size()); assertEquals(0, err.size());
                        released(language);
                    }
                }
                var load = new Load();
                for (var name : List.of("safe_write","unsafe_write","errno","dup","dup2","unlink")) {
                    for (var id : list(null,"",3L,"p0",name)) assertThrows(RuntimeFault.class,() -> load.call(name,it -> ((List<Object>) it.get(1)).set(1,id)));
                    for (var head : list(list("prim",name),list("var","foreign"),list("var","foreign",map("rep",OriginalStdioFixtures.scalar(null))))) assertThrows(RuntimeFault.class,() -> load.call(name,it -> it.set(1,head)));
                    for (var edit : List.of(list("convention","prim"),list("convention","javascript"),list("safety","interruptible"),list("arity",4.0),list("suppliedArity",0L))) assertThrows(RuntimeFault.class,() -> load.call(name,it -> descriptor(it).put((String) edit.get(0),edit.get(1))));
                    for (var unit : list(null,"main","other-package")) load.unknown(name, "unit", unit);
                    assertThrows(RuntimeFault.class,() -> load.call(name,it -> ((List<Object>) it.get(3)).set(0,true)));
                    assertThrows(RuntimeFault.class,() -> load.call(name,it -> ((List<List<Object>>) it.get(2)).getLast().set(2,map("rep",OriginalStdioFixtures.scalar("IntRep")))));
                    assertThrows(UnsupportedCore.class,() -> load.call(name,it -> ((Map<?,?>) it.get(6)).remove("foreignCall")));
                }
                assertThrows(RuntimeFault.class,() -> load.call("safe_write",it -> ((List<List<Object>>) it.get(2)).get(1).set(1,"p0")));
                for (var symbol : List.of("writev","__hscore_set_errno64",OriginalStdioFixtures.symbols.get("safe_write").replace("ZC20ZC","ZC22ZC"))) load.unknown("safe_write", "symbol", symbol);
                for (var name : List.of("dup","dup2")) {
                    assertThrows(RuntimeFault.class,() -> load.call(name,it -> ((List<List<Object>>) it.get(2)).get(0).set(1,"p" + (OriginalStdioFixtures.signatures.get(name).size() - 1))));
                    load.unknown(name, "symbol", "dup3");
                }
            } finally { context.leave(); }
        }
    }
}
