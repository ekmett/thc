// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.*;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.Json;
import thc.Language;
import java.io.*;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.*;
import java.util.function.Consumer;
import java.util.stream.LongStream;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static kotlin.Unit.INSTANCE;

/** Genuine declaration certificates in explicitly synthetic scalar consumers. */
@SuppressWarnings("unchecked")
public class NativeMallocTest {
    private final Map<String, Object> closure =
        map("kind", "closure", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", true);
    private final Map<String, Object> longRep = map("kind", "long", "primReps", List.of("IntRep"), "evaluated", true);
    private static Map<String, Object> map(Object... pairs) {
        var result = new LinkedHashMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }
    private static List<Object> list(Object... values) {
        return Arrays.asList(values);
    }
    private static Map<String, Object> plus(Map<String, Object> source, Object... pairs) {
        var result = new LinkedHashMap<>(source);
        result.putAll(map(pairs));
        return result;
    }
    private List<Map<String, Object>> declarations() throws Exception {
        try (var stream =
                 Objects.requireNonNull(getClass().getResourceAsStream("/core/original-malloc-descriptors.json"))) {
            return (List<Map<String, Object>>) Json.parse(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
    private String symbol(Map<String, Object> call) {return (String)((Map<?,?>)call.get("target")).get("symbol");
    }
    private Map<String, Object> module() throws Exception {
        return module(declarations());
    }
    private Map<String, Object> module(List<Map<String, Object>> calls) {
        return module(calls, binding -> {});
    }
    private Map<String, Object> module(List<Map<String, Object>> calls, Consumer<Map<String, Object>> mutate) {
        var bindings = new ArrayList<Map<String, Object>>();
        for (var declaration : calls) {
            String name = symbol(declaration);
            var tuple = (Map<String, Object>) declaration.get("resultRep");
            var components = (List<Map<String, Object>>) tuple.get("components");
            var formals = new ArrayList<Map<String, Object>>();
            var argumentReps = (List<Map<String, Object>>) declaration.get("argumentReps");
            for (int i = 0; i < argumentReps.size(); i++)
                formals.add(map(
                    "id", name + "-arg-" + i, "lifted", false, "rep", plus(argumentReps.get(i), "evaluated", true)));
            var call = list("app", list("var", name + "-synthetic-fcall-id", map("rep", closure)),
                formals.stream().map(it -> list("var", it.get("id"), map("rep", it.get("rep")))).toList(),
                Collections.nCopies(formals.size(), false), false, false,
                map("rep", tuple, "foreignCall", declaration));
            var ids = new ArrayList<String>();
            for (int i = 0; i < components.size(); i++) ids.add(name + "-field-" + i);
            var output = components.size() == 1 ? longRep : components.getLast();
            var result = components.size() == 1 ? list("lit", "int", "23", map("rep", longRep))
                                                : list("var", ids.getLast(), map("rep", output));
            String ctor = components.size() == 1 ? "tuple1" : "tuple2";
            var binders = new ArrayList<Map<String, Object>>();
            for (int i = 0; i < components.size(); i++)
                binders.add(map("id", ids.get(i), "lifted", false, "rep", components.get(i)));
            var body =
                list("case", call, name + "-tuple", list(list("data", ctor, ids, result, map("binders", binders))),
                    map("rep", output, "binder",
                        map("id", name + "-tuple", "lifted", false, "rep", plus(tuple, "evaluated", true))));
            var binding = map("id", name, "name", name, "arity", formals.size(), "lifted", true, "rep", closure, "expr",
                list("lam", formals, body, map("rep", closure, "resultRep", output)));
            mutate.accept(binding);
            bindings.add(binding);
        }
        bindings.addAll(memoryBindings());
        return map("schema", 1, "module", "SyntheticMallocConsumers", "unit", "test", "ghc", "9.14.1", "instrument",
            true, "bindings", bindings, "constructors",
            list(map("id", "tuple1", "name", "Solo#", "kind", "unboxed-tuple", "arity", 1, "tag", 1),
                map("id", "tuple2", "name", "(#,#)", "kind", "unboxed-tuple", "arity", 2, "tag", 1)));
    }
    private Map<String, Object> rep(String primitive) {
        return map("kind",
            primitive == null                 ? "void"
                : primitive.equals("AddrRep") ? "address"
                                              : "long",
            "primReps", primitive == null ? List.of() : List.of(primitive), "evaluated", true);
    }
    private Map<String, Object> binding(String name, String primitive, List<String> arguments, String output) {
        var formals = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < arguments.size(); i++)
            formals.add(map("id", name + "-" + i, "lifted", false, "rep", rep(arguments.get(i))));
        var result = rep(output);
        var call = list("app", list("prim", primitive),
            formals.stream().map(it -> list("var", it.get("id"), map("rep", it.get("rep")))).toList(),
            Collections.nCopies(arguments.size(), false), false, false, map("rep", result));
        var body = output != null
            ? call
            : list("case", call, name + "-state",
                  list(list("default", null, List.of(), list("lit", "int", "23", map("rep", longRep)))),
                  map("rep", longRep, "binder", map("id", name + "-state", "lifted", false, "rep", result)));
        return map("id", name, "name", name, "arity", arguments.size(), "lifted", true, "rep", closure, "expr",
            list("lam", formals, body, map("rep", closure, "resultRep", output == null ? longRep : result)));
    }
    private List<Map<String, Object>> memoryBindings() {
        return List.of(
            binding("store", "writeWord64OffAddr#", Arrays.asList("AddrRep", "IntRep", "Word64Rep", null), null),
            binding("load", "indexWord64OffAddr#", List.of("AddrRep", "IntRep"), "Word64Rep"),
            binding("loadByte", "indexWord8OffAddr#", List.of("AddrRep", "IntRep"), "Word8Rep"),
            binding(
                "copy", "copyAddrToAddrNonOverlapping#", Arrays.asList("AddrRep", "AddrRep", "IntRep", null), null));
    }
    private ExecutableProgram load(Language language, String backend, Map<String, Object> module) {
        return backend.equals("ast") ? new Program(language, module) : new BytecodeProgram(language, module);
    }
    private Context context(boolean inlining) {
        return Context.newBuilder("thc")
            .allowNativeAccess(true)
            .allowExperimentalOptions(true)
            .option("compiler.Inlining", Boolean.toString(inlining))
            .option("engine.BackgroundCompilation", "false")
            .option("engine.MultiTier", "false")
            .option("engine.CompilationFailureAction", "Throw")
            .option("engine.SingleTierCompilationThreshold", "10000000")
            .build();
    }
    private void valid(RootCallTarget target) throws Exception {
        assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
    }
    private void released(Language language) {
        var handoff = language.getHandoffState().get();
        assertEquals(0, handoff.getArguments().getDepth());
        assertEquals(0, handoff.getResults().getDepth());
        assertEquals(0, handoff.getArguments().retainedReferences());
        assertEquals(0, handoff.getResults().retainedReferences());
    }
    private void supported() {
        assumeTrue((System.getProperty("os.name").equals("Linux") || WindowsDirectoryStreams.supportedHost())
            && Set.of("amd64", "x86_64").contains(System.getProperty("os.arch")));
    }
    private interface Body {
        void run(Language language) throws Exception;
    }
    private void inside(Body body) throws Exception {
        supported();
        try (var context = context(false)) {
            context.initialize("thc");
            context.enter();
            try {
                body.run(TruffleLanguage.LanguageReference.create(Language.class).get(null));
            } finally {
                context.leave();
            }
        }
    }
    private static <T extends Throwable, R> R rethrow(Throwable error) throws T {
        throw (T) error;
    }
    private static Object invoke(MethodHandle handle, Object... arguments) {
        try {
            return handle.invokeWithArguments(arguments);
        } catch (Throwable error) {
            return rethrow(error);
        }
    }
    private static boolean await(CountDownLatch latch, long time, TimeUnit unit) {
        try {
            return latch.await(time, unit);
        } catch (InterruptedException error) {
            return rethrow(error);
        }
    }
    @Test
    public void reallocPreservesPrefixAndInvalidatesOldAliasesOnBothInstalledBackends() throws Exception {
        // Synthetic consumer; EnvironmentFullCore audits and executes the real
        // installed GHC realloc declaration through System.Environment.setEnv.
        var originals = declarations();
        var argumentReps = new ArrayList<>((List<Object>) originals.getFirst().get("argumentReps"));
        argumentReps.addFirst(((List<?>) originals.getLast().get("argumentReps")).getFirst());
        var declaration = plus(originals.getFirst(), "target",
            plus((Map<String, Object>) originals.getFirst().get("target"), "symbol", "realloc"), "arity", 3,
            "suppliedArity", 3, "argumentReps", argumentReps);
        for (String backend : List.of("ast", "bytecode"))
            inside(language -> {
                var registry = Language.currentState().getNativeAllocations();
                var program = load(language, backend, module(List.of(declaration)));
                var target = program.entryTarget("realloc");
                class Exercise {
                    boolean compiled = false;
                    ManagedAddress resize(ManagedAddress address, long size) throws Exception {
                        long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                        var result = (ManagedAddress) Calls.target(target, new Object[] {0L, address, size, INSTANCE});
                        assertEquals(before + (compiled ? 1 : 0),
                            ((Number) program.diagnostics().get("compiledEntries")).longValue());
                        if (compiled)
                            valid(target);
                        released(language);
                        return result;
                    }
                    void run() throws Exception {
                        var original = resize(ManagedAddress.nullAddress(), 8);
                        for (long index = 0; index < 8; index++) original.writeWord8(index, index + 17);
                        var grown = resize(original, 32);
                        assertThrows(RuntimeFault.class, () -> original.readWord8(0));
                        assertEquals(LongStream.range(17, 25).boxed().toList(),
                            LongStream.range(0, 8).map(grown::readWord8).boxed().toList());
                        var shrunk = resize(grown, 3);
                        assertThrows(RuntimeFault.class, () -> grown.readWord8(0));
                        assertEquals(
                            List.of(17L, 18L, 19L), LongStream.range(0, 3).map(shrunk::readWord8).boxed().toList());
                        assertSame(ManagedAddress.nullAddress(), resize(shrunk, Long.MAX_VALUE));
                        assertEquals(18L, shrunk.readWord8(1));
                        assertEquals(1, registry.liveCount());
                        assertSame(ManagedAddress.nullAddress(), resize(shrunk, 0));
                        assertThrows(RuntimeFault.class, () -> shrunk.readWord8(0));
                        assertEquals(0, registry.liveCount());
                    }
                }
                var exercise = new Exercise();
                exercise.run();
                target.getClass().getMethod("compile", boolean.class).invoke(target, true);
                valid(target);
                exercise.compiled = true;
                exercise.run();
                var live = registry.malloc(8);
                for (var bad : List.of(live.plus(1), ManagedAddress.fromHex("41"),
                         ManagedAddress.unownedNumeric(live.toNativeBits())))
                    assertThrows(RuntimeFault.class, () -> registry.realloc(bad, 16));
                assertThrows(RuntimeFault.class, () -> registry.realloc(live, -1));
                live.withNativeBorrow(
                    () -> assertThrows(RuntimeFault.class, () -> registry.realloc(live, 16)));
                registry.free(live);
            });
    }
    @Test
    public void originalDeclarationsExecuteOnFirstInstalledAstAndBytecodeEntries() throws Exception {
        supported();
        // Descriptors are unmodified core/122.json from the retained original
        // ghc-internal module, SHA256 8606e3a7f8c0089f071cf3fd9bc2e6c8374c9ff44433ce32ae3b043db45a175a.
        for (String backend : List.of("ast", "bytecode"))
            inside(language -> {
                var root = new File(System.getProperty("thc.projectRoot"));
                var manifest = (Map<String, Object>) Json.parse(
                    Files.readString(root.toPath().resolve("build/native-malloc/manifest.json")));
                assertEquals("9.14.1", manifest.get("ghc"));
                OriginalStdioChecks.INSTANCE.hashes(root, manifest.get("inputHashes"),
                    Set.of("compiler/test-fixtures/NativeMallocNative.hs",
                        "test/haskell-fixtures/NativeAddressFixtures.hs",
                        "src/test/resources/core/original-malloc-descriptors.json"),
                    null);
                OriginalStdioChecks.INSTANCE.hashes(root, manifest.get("artifactHashes"),
                    Set.of("build/native-malloc/oracle.txt"), "build/native-malloc/");
                var oracle = Files.readAllLines(root.toPath().resolve("build/native-malloc/oracle.txt"))
                                 .stream()
                                 .filter(s -> !s.isBlank())
                                 .map(s -> Arrays.stream(s.split(" ", -1)).map(Long::valueOf).toList())
                                 .toList();
                assertEquals(List.of(0L, 1L, 2L, 197L), oracle.stream().map(it -> it.get(0)).toList());
                var program = load(language, backend, module());
                var targets = new LinkedHashMap<String, RootCallTarget>();
                for (String name : List.of("malloc", "free", "store", "load", "copy"))
                    targets.put(name, program.entryTarget(name));
                var registry = Language.currentState().getNativeAllocations();
                class Exercise {
                    boolean compiled = false;
                    Object call(String name, Object... values) throws Exception {
                        var target = targets.get(name);
                        long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                        Object[] args = new Object[values.length + 1];
                        args[0] = 0L;
                        System.arraycopy(values, 0, args, 1, values.length);
                        var result = Calls.target(target, args);
                        if (compiled) {
                            assertEquals(
                                before + 1, ((Number) program.diagnostics().get("compiledEntries")).longValue());
                            valid(target);
                        }
                        released(language);
                        return result;
                    }
                    void run(long seed) throws Exception {
                        var base = (ManagedAddress) call("malloc", 24L, INSTANCE);
                        assertNotSame(ManagedAddress.nullAddress(), base);
                        for (long offset = 0; offset < 24; offset++) base.writeWord8(offset, 0);
                        var alias = base.plus(7);
                        alias.writeWord8(0, seed);
                        assertEquals(seed & 255, base.readWord8(7));
                        assertEquals(23L, call("store", base, 1L, seed * 257, INSTANCE));
                        assertEquals(seed * 257, call("load", base, 1L));
                        var copy = (ManagedAddress) call("malloc", 24L, INSTANCE);
                        assertEquals(23L, call("copy", base, copy, 24L, INSTANCE));
                        assertEquals(base.readWord8(7), copy.readWord8(7));
                        var matching = oracle.stream().filter(it -> it.get(0) == seed).toList();
                        if (matching.size() != 1)
                            throw new IllegalArgumentException("Expected one oracle row");
                        assertEquals(matching.getFirst(),
                            List.of(
                                seed, base.readWord8(7), ManagedAddressRead.WORD64.read(base, 1), copy.readWord8(7)));
                        assertEquals(23L, call("free", copy, INSTANCE));
                        assertEquals(23L, call("free", base, INSTANCE));
                        assertThrows(RuntimeFault.class, () -> alias.readWord8(0));
                        assertEquals(0, registry.liveCount());
                    }
                }
                var exercise = new Exercise();
                for (int i = 0; i < 3; i++) exercise.run(i);
                for (var entry : targets.entrySet()) {
                    var target = entry.getValue();
                    assertDoesNotThrow(
                        ()
                            -> target.getClass().getMethod("compile", boolean.class).invoke(target, true),
                        "First compilation of " + backend + "/" + entry.getKey());
                    valid(target);
                }
                exercise.compiled = true;
                exercise.run(197);
                assertEquals(23L, exercise.call("free", ManagedAddress.nullAddress(), INSTANCE));
                exercise.compiled = false;
                assertThrows(RuntimeFault.class, () -> Calls.target(targets.get("malloc"), new Object[] {0L, 8L, 7L}));
                assertEquals(0, registry.liveCount());
            });
    }
    @Test
    public void pinnedStorageCopyInstallsOnFirstAstCompilation() throws Exception {
        pinnedStorageCopy("ast");
    }
    @Test
    public void pinnedStorageCopyInstallsOnFirstBytecodeCompilation() throws Exception {
        pinnedStorageCopy("bytecode");
    }
    /**
     * Exercise the existing bulk-copy path without malloc declarations, the
     * native allocation registry, or the platform allocator DLL.
     */
    private void pinnedStorageCopy(String backend) throws Exception {
        inside(language -> {
            var program = load(language, backend, module(List.of()));
            var target = program.entryTarget("copy");
            var source = ManagedAddress.fromAllocation(ManagedAllocation.mutable(24, 8, true));
            var destination = ManagedAddress.fromAllocation(ManagedAllocation.mutable(24, 8, true));
            class Exercise {
                void run(long seed, boolean compiled) throws Exception {
                    for (long offset = 0; offset < 24; offset++) source.writeWord8(offset, seed + offset);
                    long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                    assertEquals(23L, Calls.target(target, new Object[] {0L, source, destination, 24L, INSTANCE}));
                    assertEquals(before + (compiled ? 1 : 0),
                        ((Number) program.diagnostics().get("compiledEntries")).longValue());
                    assertEquals(LongStream.range(0, 24).map(it -> (seed + it) & 255).boxed().toList(),
                        LongStream.range(0, 24).map(destination::readWord8).boxed().toList());
                    if (compiled)
                        valid(target);
                    released(language);
                }
            }
            var exercise = new Exercise();
            exercise.run(17, false);
            assertDoesNotThrow(()
                                   -> target.getClass().getMethod("compile", boolean.class).invoke(target, true),
                "First compilation of " + backend + "/copy with pinned storage");
            valid(target);
            exercise.run(197, true);
        });
    }
    @Test
    public void nativeWritesAndAllAliasesShareLiveStorageWithCheckedOwnership() throws Exception {
        inside(language -> {
            var registry = Language.currentState().getNativeAllocations();
            var base = registry.malloc(64);
            var alias = base.plus(8);
            var linker = Linker.nativeLinker();
            var memset = linker.downcallHandle(linker.defaultLookup().find("memset").orElseThrow(),
                FunctionDescriptor.of(
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));
            base.withNativeSegment(segment -> invoke(memset, segment, 0, 64L));
            alias.withNativeSegment(segment -> invoke(memset, segment, 0xa5, 8L));
            assertEquals(Collections.nCopies(8, 0xa5L), LongStream.range(8, 16).map(base::readWord8).boxed().toList());
            alias.writeNativeScalar(0, 8, 0x102030405060708L);
            alias.withNativeSegment(segment -> {
                assertEquals(0x102030405060708L, segment.get(ValueLayout.JAVA_LONG_UNALIGNED, 0));
                return INSTANCE;
            });
            var pinned = ManagedAddress.fromAllocation(ManagedAllocation.mutable(64, 8));
            base.copyNonOverlappingTo(pinned, 64);
            pinned.writeWord8(9, 37);
            pinned.copyNonOverlappingTo(base, 64);
            assertEquals(37L, alias.readWord8(1));
            assertEquals(base.toNativeBits() + 8, alias.toNativeBits());
            assertTrue(base.plus(64).plus(-64).sameLocation(base));
            for (var bad : List.of(alias, base.plus(64), pinned, ManagedAddress.fromHex("41"),
                     ManagedAddress.unownedNumeric(base.toNativeBits()))) {
                assertThrows(RuntimeFault.class, () -> registry.free(bad));
                assertEquals(1, registry.liveCount());
            }
            var sentinel = base.plus(-1);
            assertTrue(sentinel.plus(1).sameLocation(base));
            assertEquals(-1L, sentinel.difference(base));
            assertThrows(RuntimeFault.class, () -> sentinel.readWord8(0));
            assertThrows(RuntimeFault.class, () -> sentinel.writeWord8(0, 0));
            assertThrows(RuntimeFault.class, () -> base.plus(Long.MAX_VALUE).readWord8(0));
            assertThrows(RuntimeFault.class, () -> alias.plus(Long.MIN_VALUE).readWord8(0));
            assertThrows(RuntimeFault.class, () -> base.writeNativeScalar(Long.MAX_VALUE, 8, 0));
            assertThrows(RuntimeFault.class, () -> base.plus(64).readWord8(0));
            assertThrows(RuntimeFault.class, base::rawBacking);
            assertThrows(RuntimeFault.class, base::cbitsBacking);
            assertThrows(RuntimeFault.class, () -> base.writeAddressElementIndex(0, pinned));
            registry.free(base);
            assertThrows(RuntimeFault.class, () -> registry.free(base));
            assertThrows(RuntimeFault.class, alias::toNativeBits);
            assertThrows(RuntimeFault.class, () -> alias.writeWord8(0, 1));
            assertThrows(RuntimeFault.class, () -> sentinel.plus(1));
            var zero = registry.malloc(0);
            if (zero != ManagedAddress.nullAddress()) {
                zero.requireRange(0, 0, false);
                assertThrows(RuntimeFault.class, () -> zero.readWord8(0));
            }
            registry.free(zero);
            assertThrows(RuntimeFault.class, () -> registry.malloc(Long.MIN_VALUE));
            assertSame(ManagedAddress.nullAddress(), registry.malloc(Long.MAX_VALUE));
            assertTrue(
                Language.currentState().getStdio().errno() > 0, "Actual libc allocation failure must preserve errno");
            assertEquals(0, registry.liveCount());
        });
    }
    @Test
    public void windowsCrtErrnoIsCapturedOnTheCallingThreadWithoutChangingLastError() throws Exception {
        assumeTrue(WindowsDirectoryStreams.supportedHost());
        inside(language -> {
            var state = Language.currentState();
            state.getStdio().setErrno(73);
            state.getWindowsCodePages().lastError.set(0x12345678L);
            var allocation = state.getNativeAllocations().malloc(16);
            state.getNativeAllocations().free(allocation);
            assertEquals(73L, state.getStdio().errno(), "Successful allocation preserves sticky errno");
            assertSame(ManagedAddress.nullAddress(), state.getNativeAllocations().malloc(Long.MAX_VALUE));
            assertEquals(WindowsCodePages.Abi.getErrno().get("ENOMEM"), state.getStdio().errno());
            assertEquals(0x12345678L, state.getWindowsCodePages().lastError.get());
            // The second guest carrier has its own errno slot, even though the
            // process-wide bridge pairs allocations with the same native CRT.
            var executor = Executors.newSingleThreadExecutor();
            try {
                executor
                    .submit(() -> {
                        inside(otherLanguage -> {
                            var other = Language.currentState();
                            assertEquals(0L, other.getStdio().errno());
                            other.getStdio().setErrno(91);
                            var owned = other.getNativeAllocations().malloc(32);
                            other.getNativeAllocations().free(owned);
                            assertEquals(91L, other.getStdio().errno());
                        });
                        return null;
                    })
                    .get(10, TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
            }
            assertEquals(WindowsCodePages.Abi.getErrno().get("ENOMEM"), state.getStdio().errno());
            assertEquals(0, state.getNativeAllocations().liveCount());
        });
    }
    @Test
    public void freeWaitsForBorrowAndDisposalInvalidatesSavedAliases() throws Exception {
        supported();
        var context = context(false);
        var executor = Executors.newSingleThreadExecutor();
        context.initialize("thc");
        context.enter();
        var registry = Language.currentState().getNativeAllocations();
        var base = registry.malloc(8);
        var borrow = Objects.requireNonNull(base.nativeAllocation()).borrow();
        try {
            assertThrows(RuntimeFault.class, () -> registry.free(base));
            var started = new CountDownLatch(1);
            var freeing = executor.submit(() -> {
                context.enter();
                try {
                    started.countDown();
                    registry.free(base);
                } finally {
                    context.leave();
                }
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> freeing.get(100, TimeUnit.MILLISECONDS));
            borrow.segment().set(ValueLayout.JAVA_BYTE, 0, (byte) 73);
            borrow.close();
            freeing.get(5, TimeUnit.SECONDS);
            assertThrows(RuntimeFault.class, () -> base.readWord8(0));
            var saved = registry.malloc(8).plus(3);
            Language.currentState().getSavedTermios().set(0, saved);
            assertSame(saved, Language.currentState().getSavedTermios().get(0));
            registry.close();
            assertThrows(RuntimeFault.class, () -> saved.readWord8(0));
            assertThrows(RuntimeFault.class, () -> registry.malloc(1));
        } finally {
            borrow.close();
            context.leave();
            executor.shutdownNow();
            context.close();
        }
    }
    private ReentrantReadWriteLock lock(ManagedAddress address) throws Exception {
        var owner = Objects.requireNonNull(address.nativeAllocation());
        var field = owner.getClass().getDeclaredField("lifetime");
        field.setAccessible(true);
        return (ReentrantReadWriteLock) field.get(owner);
    }
    @Test
    public void borrowedCallbacksKeepSingleAndOrderedPairLifetimesOnReturnAndFailure() throws Exception {
        inside(language -> {
            var registry = Language.currentState().getNativeAllocations();
            var first = registry.malloc(8);
            var second = registry.malloc(8);
            var managed = ManagedAddress.fromByteArray(new byte[8]);
            var firstLock = lock(first);
            var secondLock = lock(second);
            var failure = new IllegalStateException("borrowed pair callback failure");
            try {
                for (var address : List.of(managed, first)) {
                    int[] calls = {0};
                    assertNull(address.withNativeBorrow(() -> {
                        calls[0]++;
                        assertEquals(address == first ? 1 : 0, firstLock.getReadHoldCount());
                        return null;
                    }));
                    assertEquals(1, calls[0]);
                    assertEquals(0, firstLock.getReadHoldCount());
                }
                for (var pair : List.of(List.of(managed, managed), List.of(managed, first), List.of(first, managed),
                         List.of(first, first.plus(1)), List.of(first, second), List.of(second, first))) {
                    var source = pair.get(0);
                    var destination = pair.get(1);
                    for (boolean throwsFailure : new boolean[] {false, true}) {
                        int[] calls = {0};
                        java.util.function.Supplier<ManagedAddress> checked =
                            () -> source.withNativeBorrows(destination, () -> {
                            calls[0]++;
                            var owners = Arrays.asList(source.nativeAllocation(),
                                destination.nativeAllocation());
                            assertEquals(owners.contains(first.nativeAllocation()) ? 1 : 0,
                                firstLock.getReadHoldCount());
                            assertEquals(owners.contains(second.nativeAllocation()) ? 1 : 0,
                                secondLock.getReadHoldCount());
                            if (firstLock.getReadHoldCount() != 0)
                                assertThrows(RuntimeFault.class, () -> registry.free(first));
                            if (secondLock.getReadHoldCount() != 0)
                                assertThrows(RuntimeFault.class, () -> registry.free(second));
                            if (throwsFailure)
                                throw failure;
                            return destination;
                        });
                        if (throwsFailure)
                            assertSame(failure, assertThrows(IllegalStateException.class, checked::get));
                        else
                            assertSame(destination, checked.get());
                        assertEquals(1, calls[0]);
                        assertEquals(0, firstLock.getReadHoldCount());
                        assertEquals(0, secondLock.getReadHoldCount());
                    }
                }
                // A failed second acquisition must release the first, without entering the body.
                var ordered = new ArrayList<>(List.of(first, second));
                ordered.sort(
                    (a, b)
                        -> Integer.compareUnsigned(System.identityHashCode(a.nativeAllocation()),
                            System.identityHashCode(b.nativeAllocation())));
                registry.free(ordered.get(1));
                for (var pair : List.of(ordered, ordered.reversed())) {
                    var source = pair.get(0);
                    var destination = pair.get(1);
                    int[] calls = {0};
                    assertThrows(RuntimeFault.class,
                        () -> source.withNativeBorrows(destination, () -> calls[0]++));
                    assertEquals(0, calls[0]);
                    assertEquals(0, firstLock.getReadHoldCount());
                    assertEquals(0, secondLock.getReadHoldCount());
                }
                registry.free(ordered.get(0));
                assertEquals(0, registry.liveCount());
            } finally {
                registry.close();
            }
        });
    }
    @Test
    public void primitiveNativeCallbacksKeepSlicesAndReleaseOnFailure() throws Exception {
        inside(language -> {
            var registry = Language.currentState().getNativeAllocations();
            var nativeAddress = registry.malloc(16);
            var pinnedOwner = ManagedAllocation.mutable(16, 8, true);
            var pinned = ManagedAddress.fromAllocation(pinnedOwner);
            try {
                for (var base : List.of(nativeAddress, pinned)) {
                    var alias = base.plus(8);
                    assertEquals(Long.MIN_VALUE, alias.withNativeSegmentLong(segment -> {
                        if (base == pinned)
                            assertTrue(Thread.holdsLock(pinnedOwner));
                        assertEquals(8L, segment.byteSize());
                        segment.set(ValueLayout.JAVA_LONG, 0, Long.MIN_VALUE);
                        return segment.get(ValueLayout.JAVA_LONG, 0);
                    }));
                    assertEquals(Long.MIN_VALUE, ManagedAddressRead.WORD64.read(base, 1));
                    var failure = new IllegalStateException("primitive callback failure");
                    assertSame(failure,
                        assertThrows(IllegalStateException.class,
                            () -> alias.withNativeSegmentLong(segment -> { throw failure; })));
                    assertFalse(Thread.holdsLock(pinnedOwner));
                    assertEquals(Long.MIN_VALUE,
                        alias.withNativeSegmentLong(
                            segment -> segment.get(ValueLayout.JAVA_LONG, 0)));
                }
                nativeAddress.withNativeSegmentLong(segment -> {
                    assertThrows(RuntimeFault.class, () -> registry.free(nativeAddress));
                    return 0L;
                });
            } finally {
                registry.free(nativeAddress);
            }
            assertThrows(
                RuntimeFault.class, () -> nativeAddress.withNativeSegmentLong(segment -> 0L));
            assertEquals(0, registry.liveCount());
        });
    }
    @Test
    public void primitiveNativeCallbackHoldsBorrowUntilNormalOrExceptionalReturn() throws Exception {
        supported();
        try (var context = context(false)) {
            context.initialize("thc");
            context.enter();
            try {
                var registry = Language.currentState().getNativeAllocations();
                for (boolean throwsFailure : new boolean[] {false, true}) {
                    var base = registry.malloc(8);
                    var entered = new CountDownLatch(1);
                    var release = new CountDownLatch(1);
                    var freeingStarted = new CountDownLatch(1);
                    var freeingThread = new AtomicReference<Thread>();
                    var lifetime = lock(base);
                    var failure = new IllegalStateException("borrowed callback failure");
                    var pool = Executors.newFixedThreadPool(2);
                    try {
                        var reading = pool.submit(() -> {
                            context.enter();
                            try {
                                return base.plus(3).withNativeSegmentLong(segment -> {
                                    entered.countDown();
                                    if (!await(release, 10, TimeUnit.SECONDS))
                                        throw new IllegalStateException("Check failed.");
                                    segment.set(ValueLayout.JAVA_BYTE, 0, (byte) 73);
                                    if (throwsFailure)
                                        throw failure;
                                    return (long) segment.get(ValueLayout.JAVA_BYTE, 0);
                                });
                            } finally {
                                context.leave();
                            }
                        });
                        assertTrue(entered.await(5, TimeUnit.SECONDS));
                        var freeing = pool.submit(() -> {
                            context.enter();
                            try {
                                freeingThread.set(Thread.currentThread());
                                freeingStarted.countDown();
                                registry.free(base);
                            } finally {
                                context.leave();
                            }
                        });
                        assertTrue(freeingStarted.await(5, TimeUnit.SECONDS));
                        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                        while (!lifetime.hasQueuedThread(freeingThread.get())) {
                            if (System.nanoTime() >= deadline)
                                throw new IllegalStateException(
                                    "Native free did not queue behind the primitive callback");
                            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
                        }
                        assertFalse(freeing.isDone());
                        release.countDown();
                        if (throwsFailure)
                            assertSame(failure,
                                assertThrows(ExecutionException.class, () -> reading.get(5, TimeUnit.SECONDS))
                                    .getCause());
                        else
                            assertEquals(73L, reading.get(5, TimeUnit.SECONDS));
                        freeing.get(5, TimeUnit.SECONDS);
                        assertThrows(RuntimeFault.class, () -> base.readWord8(0));
                        assertEquals(0, registry.liveCount());
                    } finally {
                        release.countDown();
                        pool.shutdownNow();
                        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
                    }
                }
            } finally {
                context.leave();
            }
        }
    }
    @Test
    public void nativeByteReadKeepsTheFirstInstalledEntryAndLiveAliases() throws Exception {
        for (String backend : List.of("ast", "bytecode"))
            inside(language -> {
                var registry = Language.currentState().getNativeAllocations();
                var base = registry.malloc(16);
                try {
                    var program = load(language, backend, module(List.of()));
                    var target = program.entryTarget("loadByte");
                    var alias = base.plus(8);
                    // Word8Rep retains an Int computational carrier after lowering.
                    // Assert that exact carrier, not a widened Number conversion.
                    alias.writeWord8Int(0, 128);
                    assertEquals(128, Calls.target(target, new Object[] {0L, alias, 0L}));
                    target.getClass().getMethod("compile", boolean.class).invoke(target, true);
                    valid(target);
                    var runtime = Truffle.getRuntime();
                    runtime.getClass()
                        .getMethod(
                            "bypassedInstalledCode", Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget"))
                        .invoke(runtime, target);
                    for (int value : new int[] {255, 0, 129}) {
                        base.writeWord8Int(8, value);
                        long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                        assertEquals(value, Calls.target(target, new Object[] {0L, alias, 0L}));
                        assertEquals(before + 1, ((Number) program.diagnostics().get("compiledEntries")).longValue());
                        valid(target);
                        released(language);
                    }
                } finally {
                    registry.free(base);
                }
            });
    }
    @Test
    public void termiosTransferCopiesBackWholeImagesOnSuccessAndNativeError() throws Exception {
        inside(language -> {
            assumeTrue(System.getProperty("os.name").equals("Linux"), "termios requires the native POSIX provider");
            var registry = Language.currentState().getNativeAllocations();
            int size =
                (int) TermiosImage.scalar(OriginalStdioOp.SIZEOF_TERMIOS, ManagedAddress.nullAddress(), 0);
            var base = registry.malloc(size + 16L);
            var address = base.plus(8);
            try {
                for (boolean copyBack : new boolean[] {false, true})
                    for (boolean fails : new boolean[] {false, true}) {
                        for (int i = 0; i < size + 16; i++) base.writeWord8(i, 77);
                        var failure = new NativeFileException("tcgetattr test", 5);
                        java.util.function.Supplier<Long> invoke =
                            () -> TermiosImage.transfer(address, copyBack, bytes -> {
                            byte[] expected = new byte[size];
                            Arrays.fill(expected, (byte) 77);
                            assertArrayEquals(expected, bytes);
                            // Same-thread release must reject rather than deadlock.
                            assertThrows(RuntimeFault.class, () -> registry.free(base));
                            for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (i * 17);
                            if (fails)
                                return rethrow(failure);
                            return 37L;
                        });
                        if (fails)
                            assertSame(failure, assertThrows(NativeFileException.class, invoke::get));
                        else
                            assertEquals(37L, invoke.get());
                        byte[] expected = new byte[size + 16];
                        Arrays.fill(expected, (byte) 77);
                        if (copyBack)
                            for (int i = 0; i < size; i++) expected[i + 8] = (byte) (i * 17);
                        byte[] actual = new byte[size + 16];
                        for (int i = 0; i < actual.length; i++) actual[i] = (byte) base.readWord8(i);
                        assertArrayEquals(expected, actual);
                    }
                assertThrows(RuntimeFault.class,
                    ()
                        -> TermiosImage.transfer(
                            base.plus(17), true, bytes -> fail("Short image reached native operation")));
            } finally {
                registry.free(base);
            }
        });
    }
    @Test
    public void tcgetattrTransferHoldsNativeOwnerUntilErrorOrSuccessCopybackCompletes() throws Exception {
        supported();
        assumeTrue(System.getProperty("os.name").equals("Linux"), "termios requires the native POSIX provider");
        try (var context = context(false)) {
            var executor = Executors.newSingleThreadExecutor();
            context.initialize("thc");
            context.enter();
            try {
                var registry = Language.currentState().getNativeAllocations();
                long size =
                    TermiosImage.scalar(OriginalStdioOp.SIZEOF_TERMIOS, ManagedAddress.nullAddress(), 0);
                for (boolean fails : new boolean[] {false, true}) {
                    var base = registry.malloc(size);
                    for (long i = 0; i < size; i++) base.writeWord8(i, 19);
                    var startFree = new CountDownLatch(1);
                    var freeingStarted = new CountDownLatch(1);
                    var freeing = executor.submit(() -> {
                        context.enter();
                        try {
                            assertTrue(await(startFree, 5, TimeUnit.SECONDS));
                            freeingStarted.countDown();
                            registry.free(base);
                        } finally {
                            context.leave();
                        }
                    });
                    var failure = new NativeFileException("tcgetattr test", 5);
                    java.util.function.Supplier<Long> invoke = () -> TermiosImage.transfer(base, true, bytes -> {
                        // Queue release while the same staging callback used by
                        // ManagedFiles.tcgetattr owns the complete native extent.
                        startFree.countDown();
                        assertTrue(await(freeingStarted, 5, TimeUnit.SECONDS));
                        assertThrows(TimeoutException.class, () -> freeing.get(100, TimeUnit.MILLISECONDS));
                        Arrays.fill(bytes, (byte) 73);
                        if (fails)
                            return rethrow(failure);
                        return 37L;
                    });
                    try {
                        if (fails)
                            assertSame(failure, assertThrows(NativeFileException.class, invoke::get));
                        else
                            assertEquals(37L, invoke.get());
                        // Copyback's checked accesses would fail if the queued
                        // release won before the finally block completed.
                        freeing.get(5, TimeUnit.SECONDS);
                        assertThrows(RuntimeFault.class, () -> base.readWord8(0));
                        assertEquals(0, registry.liveCount());
                    } finally {
                        startFree.countDown();
                    }
                }
            } finally {
                context.leave();
                executor.shutdownNow();
            }
        }
    }
    private void change(Object value, String mutation) {if(value instanceof Map<?,?> map){
            for (var child : map.values()) change(child, mutation);
        } else if (value instanceof List<?> list) {
            if (!list.isEmpty() && "app".equals(list.get(0))) {
                var metadata = (Map<String, Object>) list.get(6);
                var declaration = (Map<String, Object>) metadata.get("foreignCall");
                if (declaration != null)
                    switch (mutation) {
                        case "unit" -> ((Map<String, Object>) declaration.get("target")).put("unit", "other");
                        case "safety" -> declaration.put("safety", "safe");
                        case "arity" -> declaration.put("arity", 3L);
                        case "rep" ->
                            ((List<Map<String, Object>>) declaration.get("argumentReps"))
                                .get(0)
                                .put("primReps", List.of("IntRep"));
                        case "flag" -> ((List<Boolean>) list.get(3)).set(0, true);
                        case "result" ->
                            ((Map<String, Object>) declaration.get("resultRep")).put("components", List.of());
                    }
            }
            for (var child : list) change(child, mutation);
        }
    }
    @Test
    public void crossContextNativePermissionAndForgedDeclarationsFailClosed() throws Exception {
        supported();
        try (var first = context(false); var second = context(false)) {
            first.initialize("thc");
            second.initialize("thc");
            first.enter();
            ManagedAddress base;
            try {
                base = Language.currentState().getNativeAllocations().malloc(8);
            } finally {
                first.leave();
            }
            second.enter();
            try {
                assertThrows(RuntimeFault.class, () -> Language.currentState().getNativeAllocations().free(base));
                assertThrows(RuntimeFault.class, () -> base.readWord8(0));
                assertThrows(RuntimeFault.class, () -> base.plus(0));
            } finally {
                second.leave();
            }
            first.enter();
            try {
                Language.currentState().getNativeAllocations().free(base);
            } finally {
                first.leave();
            }
        }
        try (var context = Context.newBuilder("thc").build()) {
            context.initialize("thc");
            context.enter();
            try {
                assertThrows(RuntimeFault.class, () -> Language.currentState().getNativeAllocations().malloc(8));
            } finally {
                context.leave();
            }
        }
        inside(language -> {
            for (String backend : List.of("ast", "bytecode"))
                for (String mutation : List.of("unit", "safety", "arity", "rep", "flag", "result")) {
                    var malformed = (Map<String, Object>) Json.parse(Json.stringify(module()));
                    change(malformed, mutation);
                    assertThrows(
                        RuntimeFault.class, () -> load(language, backend, malformed), backend + "/" + mutation);
                }
        });
    }
}
