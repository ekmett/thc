// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import thc.runtime.Unit;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import thc.Language;
import thc.PackageScalarLink;
import thc.PackageScalarSignature;
import java.lang.foreign.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** The identical C source also belongs to the ordinary GHC/Cabal native oracle. */
class StablePointerForeignTest {
    @TempDir Path directory;
    private Path compile(boolean nativeCode) throws Exception {
        var source = Path.of(System.getProperty("thc.projectRoot"), "t/fixtures/run-stableptr-ffi/cbits/stable.c");
        var output = directory.resolve(nativeCode ? "stable.so" : "stable.bc");
        var flags = nativeCode ? List.of("-shared", "-fPIC") : List.of("-emit-llvm", "-c");
        var target = System.getProperty("os.name").equals("Linux") ? List.of("--target=" +
            (System.getProperty("os.arch").equals("amd64") ? "x86_64" : System.getProperty("os.arch")) + "-unknown-linux-gnu") : List.<String>of();
        var command = new ArrayList<>(List.of(System.getenv("THC_CLANG") == null ? "clang" : System.getenv("THC_CLANG"), "-O1"));
        command.addAll(target); command.addAll(flags);
        command.addAll(List.of(source.toString(), "-o", output.toString()));
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String log = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), log);
        return output;
    }
    private Context context(boolean nativeAccess) {
        return Context.newBuilder("thc").allowNativeAccess(nativeAccess)
            .allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false").build();
    }
    private Context context() { return context(true); }
    private static final class Entry extends RootNode {
        private final PackageScalarCall call;
        @Child private PackageScalarAccess access;
        Entry(Language language, PackageScalarCall call) { super(language); this.call = call; access = new PackageScalarAccess(call); }
        @Override public Object execute(VirtualFrame frame) {
            return switch (call.getResult()) {
                case "AddrRep" -> access.executeAddress(frame.getArguments(), Unit.INSTANCE);
                case "void" -> { access.executeVoid(frame.getArguments(), Unit.INSTANCE); yield Unit.INSTANCE; }
                default -> access.executeLong(frame.getArguments(), Unit.INSTANCE);
            };
        }
    }
    @ParameterizedTest @ValueSource(strings = {"unsafe", "safe"})
    void sulongStoresReturnsAndComparesOpaqueStablePointersAcrossCalls(String safety) throws Exception {
        byte[] bytes = Files.readAllBytes(compile(false));
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        var abi = List.of(
            new PackageScalarSignature("stable_store", "stable_store", List.of("AddrRep"), "void"),
            new PackageScalarSignature("stable_load", "stable_load", List.of(), "AddrRep"),
            new PackageScalarSignature("stable_identity", "stable_identity", List.of("AddrRep"), "AddrRep"),
            new PackageScalarSignature("stable_equal", "stable_equal", List.of("AddrRep", "AddrRep"), "Int32Rep"),
            new PackageScalarSignature("stable_clear", "stable_clear", List.of(), "void"),
            new PackageScalarSignature("stable_unknown", "stable_unknown", List.of(), "AddrRep"))
            .stream().map(signature -> new PackageScalarSignature(signature.symbol(), signature.entry(),
                signature.arguments(), signature.result(), signature.convention(), safety)).toList();
        var link = new PackageScalarLink("stable-ffi-control", "test-host", sha, sha, bytes, abi);
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var state = Language.currentState();
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                state.getPackageCbits().link(link);
                var calls = new LinkedHashMap<String, com.oracle.truffle.api.RootCallTarget>();
                for (var signature : abi) calls.put(signature.symbol(), new Entry(language, new PackageScalarCall(link, signature)).getCallTarget());
                assertSame(ManagedAddress.nullAddress(), calls.get("stable_identity").call(ManagedAddress.nullAddress()));
                var pinned = PinnedMemory.allocate(16, 64);
                var pinnedAddress = ManagedAddress.fromAllocation(pinned).plus(7);
                long pinnedBits = Objects.requireNonNull(pinned.nativeSegment()).address();
                var returnedPinned = (ManagedAddress) calls.get("stable_identity").call(pinnedAddress);
                assertEquals(pinnedBits + 7, returnedPinned.toNativeBits());
                assertEquals(pinnedBits, Objects.requireNonNull(pinned.nativeSegment()).address());
                assertThrows(RuntimeFault.class, () -> returnedPinned.readWord8(0));
                var forwardedPinned = (ManagedAddress) calls.get("stable_identity").call(returnedPinned);
                assertEquals(pinnedBits + 7, forwardedPinned.toNativeBits());
                assertEquals(1L, calls.get("stable_equal").call(returnedPinned, forwardedPinned));
                assertEquals(pinnedBits + 9, ((ManagedAddress) calls.get("stable_identity").call(returnedPinned.plus(2))).toNativeBits());
                var heap = ManagedAllocation.mutable(16, 8);
                assertThrows(RuntimeFault.class, () -> calls.get("stable_identity").call(ManagedAddress.fromAllocation(heap)));
                assertFalse(heap.isPinned());
                assertNull(heap.nativeSegment());
                if (System.getProperty("os.name").equals("Linux") && Set.of("amd64", "x86_64").contains(System.getProperty("os.arch"))) {
                    var allocation = state.getNativeAllocations().malloc(8);
                    ManagedAddress returnedAllocation;
                    try {
                        returnedAllocation = (ManagedAddress) calls.get("stable_identity").call(allocation);
                        assertEquals(allocation.toNativeBits(), returnedAllocation.toNativeBits());
                        assertThrows(RuntimeFault.class, () -> returnedAllocation.readWord8(0));
                        assertEquals(1L, calls.get("stable_equal").call(allocation, returnedAllocation));
                        ManagedAddress.withNativeBorrows(List.of(returnedAllocation), () -> {
                            assertThrows(RuntimeFault.class, () -> state.getNativeAllocations().free(allocation));
                            return Unit.INSTANCE;
                        });
                    } finally { state.getNativeAllocations().free(allocation); }
                    assertThrows(RuntimeFault.class, () -> calls.get("stable_identity").call(returnedAllocation));
                    assertThrows(RuntimeFault.class, returnedAllocation::toNativeBits);
                }
                var referent = new Object();
                var first = state.getStablePointers().make(referent);
                var second = state.getStablePointers().make(referent);
                calls.get("stable_store").call(first);
                assertEquals(1L, calls.get("stable_equal").call(first, first));
                assertEquals(0L, calls.get("stable_equal").call(first, second));
                var returned = (ManagedAddress) calls.get("stable_load").call();
                assertSame(referent, state.getStablePointers().dereference(returned));
                var again = (ManagedAddress) calls.get("stable_identity").call(returned);
                assertTrue(state.getStablePointers().equal(first, again));
                assertThrows(RuntimeFault.class, () -> again.readWord8(0));
                assertThrows(RuntimeFault.class, () -> again.plus(0));
                // A completely unrelated C pointer must not acquire byte-storage authority.
                var unrelated = (ManagedAddress) calls.get("stable_unknown").call();
                assertThrows(RuntimeFault.class, () -> unrelated.readWord8(0));
                assertThrows(RuntimeFault.class, () -> state.getStablePointers().dereference(unrelated));
                var forwardedStatic = (ManagedAddress) calls.get("stable_identity").call(unrelated);
                assertEquals(1L, calls.get("stable_equal").call(unrelated, forwardedStatic));
                assertThrows(RuntimeFault.class, () -> calls.get("stable_identity").call(
                    ManagedAddress.unownedNumeric(unrelated.toNativeBits())));
                try (var other = context()) {
                    other.initialize("thc"); other.enter();
                    try {
                        var otherLanguage = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                        var otherState = Language.currentState();
                        otherState.getPackageCbits().link(link);
                        var identities = abi.stream().filter(signature -> signature.symbol().equals("stable_identity")).toList();
                        assertEquals(1, identities.size());
                        var otherCall = new Entry(otherLanguage, new PackageScalarCall(link, identities.getFirst())).getCallTarget();
                        var rejected = assertThrows(RuntimeFault.class, () -> otherCall.call(unrelated));
                        assertTrue(Objects.requireNonNull(rejected.getMessage()).contains("Returned package C pointer belongs to another context"));
                        assertThrows(RuntimeFault.class, unrelated::toNativeBits);
                    } finally { other.leave(); }
                }
                calls.get("stable_clear").call();
                assertSame(ManagedAddress.nullAddress(), calls.get("stable_load").call());
                var token = state.getStablePointers().nativeTransport(first);
                state.getStablePointers().free(first);
                assertFalse(token.isPointer());
                assertNull(state.getStablePointers().recoverToken(token.getBits()));
                assertThrows(RuntimeFault.class, () -> state.getStablePointers().dereference(returned));
                assertThrows(RuntimeFault.class, () -> calls.get("stable_store").call(first));
                state.getStablePointers().free(second);
                state.getPackageCbits().close();
                assertThrows(RuntimeFault.class, unrelated::toNativeBits);
            } finally { context.leave(); }
        }
    }

    @Test @EnabledOnOs(OS.LINUX) @EnabledIfSystemProperty(named = "os.arch", matches = "amd64|x86_64")
    void hostNativeCStoresAndReturnsTheSameTokenAndNativeCellsRecoverIt() throws Throwable {
        try (var libraryLifetime = Arena.ofConfined()) {
            var library = SymbolLookup.libraryLookup(compile(true), libraryLifetime);
            var linker = Linker.nativeLinker();
            var store = linker.downcallHandle(library.find("stable_store").orElseThrow(), FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
            var load = linker.downcallHandle(library.find("stable_load").orElseThrow(), FunctionDescriptor.of(ValueLayout.ADDRESS));
            var equal = linker.downcallHandle(library.find("stable_equal").orElseThrow(), FunctionDescriptor.of(ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            var clear = linker.downcallHandle(library.find("stable_clear").orElseThrow(), FunctionDescriptor.ofVoid());
            try (var context = context()) {
                context.initialize("thc"); context.enter();
                try {
                    var state = Language.currentState();
                    var value = new Object();
                    var pointer = state.getStablePointers().make(value);
                    long bits = pointer.toNativeBits();
                    assertSame(value, state.getStablePointers().dereference(state.getNativeAddresses().recover(bits)));
                    var nativePointer = MemorySegment.ofAddress(bits);
                    store.invokeWithArguments(nativePointer);
                    assertEquals(1, equal.invokeWithArguments(nativePointer, nativePointer));
                    var returned = (MemorySegment) load.invokeWithArguments();
                    assertEquals(bits, returned.address());
                    assertSame(value, state.getStablePointers().dereference(Objects.requireNonNull(state.getStablePointers().recoverToken(returned.address()))));
                    var cell = state.getNativeAllocations().malloc(8);
                    try {
                        cell.writeAddressElementIndex(0, pointer);
                        assertSame(value, state.getStablePointers().dereference(cell.readAddressElementIndex(0)));
                    } finally { state.getNativeAllocations().free(cell); }
                    clear.invokeWithArguments();
                    state.getStablePointers().free(pointer);
                    assertNull(state.getStablePointers().recoverToken(bits));
                    assertThrows(RuntimeFault.class, () -> state.getStablePointers().dereference(state.getNativeAddresses().recover(bits)));
                } finally { context.leave(); }
            }
        }
    }

    @Test void nativeAccessContextOwnershipAndDisposalRemainExplicit() {
        try (var denied = context(false)) {
            denied.initialize("thc"); denied.enter();
            try {
                var registry = Language.currentState().getStablePointers();
                var pointer = registry.make(new Object());
                assertThrows(RuntimeFault.class, () -> registry.nativeTransport(pointer));
                registry.free(pointer);
            } finally { denied.leave(); }
        }
        var first = context();
        first.initialize("thc"); first.enter();
        var registry = Language.currentState().getStablePointers();
        var pointer = registry.make(new Object());
        var token = registry.nativeTransport(pointer);
        first.leave();
        try (var other = context()) {
            other.initialize("thc"); other.enter();
            try {
                var second = Language.currentState().getStablePointers();
                var local = second.make(new Object());
                assertNotEquals(token.getBits(), second.nativeToken(local), "simultaneously live contexts have distinct native identities");
                assertNull(second.recoverToken(token.getBits()));
                assertThrows(RuntimeFault.class, () -> second.nativeTransport(pointer));
                assertThrows(RuntimeFault.class, token::asPointer);
                second.free(local);
            } finally { other.leave(); }
        }
        first.close();
        assertFalse(token.isPointer());
        assertThrows(RuntimeFault.class, () -> registry.dereference(pointer));
    }
}
