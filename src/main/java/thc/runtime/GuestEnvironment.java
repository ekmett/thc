// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.nodes.Node;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import thc.Language;
import static thc.runtime.RuntimeServiceStatus.fault;

/** A context's POSIX environment. Guest changes never mutate the JVM process.
 * putenv retains the supplied string, as required by the original GHC caller;
 * getenv and environ expose the same storage, not decoded/re-encoded copies. */
public final class GuestEnvironment {
    private final TruffleLanguage.Env env;
    private List<ManagedAddress> entries;
    private ManagedAddress vector;

    public GuestEnvironment(TruffleLanguage.Env env) { this.env = env; }

    private ManagedNativeAllocations current() {
        var state = Language.currentState(null);
        if (state.getEnvironment() != this)
            throw fault("Environment belongs to another context");
        return state.getNativeAllocations();
    }

    private static byte[] snapshot(ManagedAddress address) {
        var owner = address.nativeAllocation$org_intelligence_thc();
        try (var borrow = owner == null ? null : owner.borrow()) {
            long size = address.cStringLength();
            if (size >= Integer.MAX_VALUE) throw fault("Environment string exceeds managed byte capacity");
            var bytes = new byte[(int) size];
            for (int index = 0; index < bytes.length; index++) bytes[index] = (byte) address.readWord8(index);
            return bytes;
        }
    }

    private List<ManagedAddress> contents() {
        current();
        if (entries != null) return entries;
        var allocations = current();
        var created = new ArrayList<ManagedAddress>();
        try {
            // Truffle applies the embedding host's environment-access policy.
            // The selected Linux filesystem encoding is UTF-8.
            for (var entry : env.getEnvironment().entrySet()) {
                var bytes = (entry.getKey() + "=" + entry.getValue()).getBytes(StandardCharsets.UTF_8);
                var address = allocations.malloc((long) bytes.length + 1);
                if (address == ManagedAddress.Companion.nullAddress()) throw new OutOfMemoryError("Unable to allocate environment");
                created.add(address);
                for (int index = 0; index < bytes.length; index++) address.writeWord8(index, bytes[index]);
                address.writeWord8(bytes.length, 0);
            }
            entries = created;
            return created;
        } catch (Throwable failure) {
            for (int index = created.size() - 1; index >= 0; index--) {
                try { allocations.free(created.get(index)); }
                catch (Throwable closing) { failure.addSuppressed(closing); }
            }
            throw failure;
        }
    }

    private static boolean matches(ManagedAddress entry, byte[] name) {
        var bytes = snapshot(entry);
        if (bytes.length <= name.length || bytes[name.length] != '=') return false;
        for (int index = 0; index < name.length; index++) if (name[index] != bytes[index]) return false;
        return true;
    }

    private static int equalsIndex(byte[] bytes) {
        for (int index = 0; index < bytes.length; index++) if (bytes[index] == '=') return index;
        return -1;
    }

    @TruffleBoundary
    public synchronized ManagedAddress get(ManagedAddress name) {
        current();
        var bytes = snapshot(name);
        if (bytes.length == 0 || equalsIndex(bytes) >= 0) return ManagedAddress.Companion.nullAddress();
        for (var entry : contents()) if (matches(entry, bytes)) return entry.plus((long) bytes.length + 1);
        return ManagedAddress.Companion.nullAddress();
    }

    @TruffleBoundary
    public synchronized long put(ManagedAddress entry) {
        current();
        var bytes = snapshot(entry);
        int equals = equalsIndex(bytes);
        // Linux putenv("NAME") removes NAME; the original GHC setEnv always
        // supplies NAME=VALUE. Keep the empty-name errno behavior too.
        if (equals < 0) return remove(bytes);
        var name = Arrays.copyOfRange(bytes, 0, equals);
        var values = contents();
        int index = 0;
        while (index < values.size() && !matches(values.get(index), name)) index++;
        if (index == values.size()) values.add(entry); else values.set(index, entry);
        retireVector();
        return 0;
    }

    @TruffleBoundary
    public synchronized long unset(ManagedAddress name) {
        current();
        return remove(snapshot(name));
    }

    private long remove(byte[] name) {
        if (name.length == 0 || equalsIndex(name) >= 0) {
            Language.currentState(null).getStdio().nativeError(22); // Linux EINVAL.
            return -1;
        }
        var values = contents();
        int destination = 0;
        for (int index = 0; index < values.size(); index++) {
            var entry = values.get(index);
            if (!matches(entry, name)) {
                if (destination != index) values.set(destination, entry);
                destination++;
            }
        }
        if (destination < values.size()) {
            for (int index = values.size() - 1; index >= destination; index--) values.remove(index);
            retireVector();
        }
        return 0;
    }

    @TruffleBoundary
    public synchronized ManagedAddress environ() {
        var allocations = current();
        if (vector != null) return vector;
        var values = contents();
        var address = allocations.malloc(((long) values.size() + 1) * 8);
        if (address == ManagedAddress.Companion.nullAddress()) throw new OutOfMemoryError("Unable to allocate environment vector");
        try {
            for (int index = 0; index < values.size(); index++) address.writeAddressElementIndex(index, values.get(index));
            address.writeAddressElementIndex(values.size(), ManagedAddress.Companion.nullAddress());
            vector = address;
            return address;
        } catch (Throwable failure) {
            try { allocations.free(address); }
            catch (Throwable closing) { failure.addSuppressed(closing); }
            throw failure;
        }
    }

    private void retireVector() {
        var old = vector;
        vector = null;
        if (old != null) current().free(old);
        // Returned strings remain live until their original owner releases them
        // or the context closes. Caller-owned putenv strings are never freed here.
    }

    public static final class ProcessSnapshot {
        private final List<byte[]> entries;
        private final byte[] searchPath;
        ProcessSnapshot(List<byte[]> entries, byte[] searchPath) {
            this.entries = entries;
            this.searchPath = searchPath;
        }
        public List<byte[]> getEntries() { return entries; }
        public byte[] getSearchPath() { return searchPath; }
    }

    /** One raw-byte snapshot of this context's environment and executable PATH.
     * An explicit child environment must not replace this parent PATH. */
    @TruffleBoundary
    public synchronized ProcessSnapshot snapshotForProcess() {
        var values = new ArrayList<byte[]>();
        for (var entry : contents()) values.add(snapshot(entry));
        byte[] path = null;
        for (var bytes : values) {
            if (bytes.length >= 5 && bytes[0] == 'P' && bytes[1] == 'A' && bytes[2] == 'T' &&
                bytes[3] == 'H' && bytes[4] == '=') {
                path = Arrays.copyOfRange(bytes, 5, bytes.length);
                break;
            }
        }
        return new ProcessSnapshot(values, path);
    }

    public static GuestEnvironment current(Node node) {
        return Language.currentState(node).getEnvironment();
    }
}
