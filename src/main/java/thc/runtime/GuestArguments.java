// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.nodes.Node;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import thc.Language;
import static thc.runtime.RuntimeServiceStatus.fault;

/** Context-owned copies of GHC's argc/argv, including argv[0] and the final
 * null pointer. No process-global RTS state or host command line is consulted. */
public final class GuestArguments {
    private record Image(int count, ManagedAddress vector, List<ManagedAddress> strings) {}
    private String[] initial;
    private Image image;

    public GuestArguments(TruffleLanguage.Env env) {
        var arguments = env.getApplicationArguments();
        initial = new String[arguments.length + 1];
        initial[0] = "";
        System.arraycopy(arguments, 0, initial, 1, arguments.length);
    }

    private ManagedNativeAllocations current() {
        var state = Language.currentState(null);
        if (state.getArguments$org_intelligence_thc() != this)
            throw fault("Program arguments belong to another context");
        return state.getNativeAllocations$org_intelligence_thc();
    }

    /** The owning CLI supplies argv before any guest code has observed it. */
    @TruffleBoundary
    public synchronized void initialize(String programName, String[] arguments) {
        current();
        if (image != null) throw fault("Program arguments have already been observed");
        initial = new String[arguments.length + 1];
        initial[0] = programName;
        System.arraycopy(arguments, 0, initial, 1, arguments.length);
    }

    @TruffleBoundary
    public synchronized void get(ManagedAddress argc, ManagedAddress argv) {
        current();
        var nil = ManagedAddress.Companion.nullAddress();
        if (argc != nil) argc.requireByteRegion$org_intelligence_thc(4, true);
        if (argv != nil) argv.requireRange(0, 8, true);
        var value = image;
        if (value == null) {
            var bytes = new ArrayList<byte[]>(initial.length);
            for (var argument : initial) bytes.add(encode(argument));
            value = create(bytes);
            image = value;
            initial = new String[0];
        }
        if (argc != nil) argc.writeNativeScalar(0, 4, value.count());
        if (argv != nil) argv.writeAddressElementIndex(0, value.vector());
    }

    /** The original setProgArgv copies strings before its caller releases the
     * temporary vector. Read every input before retiring the previous image. */
    @TruffleBoundary
    public synchronized void set(long argc, ManagedAddress argv) {
        var allocations = current();
        if (argc < 0 || argc > Integer.MAX_VALUE) throw fault("Program argument count is outside CInt range");
        List<byte[]> bytes = List.of();
        if (argc != 0) {
            var owner = argv.nativeAllocation$org_intelligence_thc();
            try (var borrow = owner == null ? null : owner.borrow()) {
                argv.requireRange(0, argc * 8, false);
                bytes = new ArrayList<>((int) argc);
                for (int index = 0; index < argc; index++) {
                    var address = argv.readAddressElementIndex(index);
                    var stringOwner = address.nativeAllocation$org_intelligence_thc();
                    try (var stringBorrow = stringOwner == null ? null : stringOwner.borrow()) {
                        long size = address.cStringLength();
                        if (size >= Integer.MAX_VALUE) throw fault("Program argument exceeds managed byte capacity");
                        var value = new byte[(int) size];
                        for (int offset = 0; offset < value.length; offset++)
                            value[offset] = (byte) address.readWord8(offset);
                        bytes.add(value);
                    }
                }
            }
        }
        var replacement = create(bytes);
        var old = image;
        image = replacement;
        initial = new String[0];
        if (old != null) release(allocations, old);
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> byte[] encode(String value) throws E {
        if (value.indexOf('\0') >= 0) throw fault("A program argument cannot contain NUL");
        var encoder = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            var buffer = encoder.encode(CharBuffer.wrap(value));
            var bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            return bytes;
        } catch (CharacterCodingException failure) {
            // Preserve the original exception across the unchecked guest entry.
            throw (E) failure;
        }
    }

    private Image create(List<byte[]> bytes) {
        var allocations = current();
        var owned = new ArrayList<ManagedAddress>(bytes.size() + 1);
        try {
            var vector = allocate(allocations, owned, ((long) bytes.size() + 1) * 8);
            var strings = new ArrayList<ManagedAddress>(bytes.size());
            for (int index = 0; index < bytes.size(); index++) {
                var value = bytes.get(index);
                var address = allocate(allocations, owned, (long) value.length + 1);
                for (int offset = 0; offset < value.length; offset++) address.writeWord8(offset, value[offset]);
                address.writeWord8(value.length, 0);
                vector.writeAddressElementIndex(index, address);
                strings.add(address);
            }
            vector.writeAddressElementIndex(bytes.size(), ManagedAddress.Companion.nullAddress());
            return new Image(bytes.size(), vector, strings);
        } catch (Throwable failure) {
            for (int index = owned.size() - 1; index >= 0; index--) {
                try { allocations.free(owned.get(index)); }
                catch (Throwable closing) { failure.addSuppressed(closing); }
            }
            throw failure;
        }
    }

    private static ManagedAddress allocate(ManagedNativeAllocations allocations, List<ManagedAddress> owned, long size) {
        var address = allocations.malloc(size);
        if (address == ManagedAddress.Companion.nullAddress()) throw new OutOfMemoryError("Unable to allocate program arguments");
        owned.add(address);
        return address;
    }

    private static void release(ManagedNativeAllocations allocations, Image value) {
        for (var address : value.strings()) allocations.free(address);
        allocations.free(value.vector());
    }

    // The existing native allocation registry releases the final image during
    // context disposal, and all escaped addresses retain its lifetime checks.
    public static GuestArguments current(Node node) {
        return Language.currentState(node).getArguments$org_intelligence_thc();
    }
}
