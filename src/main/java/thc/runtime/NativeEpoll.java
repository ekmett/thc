// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.function.Consumer;

/** A distinct duplicate per logical descriptor preserves kernel registrations
 * for dup aliases without exposing process fd integers to Core. */
public final class NativeEpoll implements AutoCloseable {
    private final int descriptor;
    private final LinkedHashMap<Object, Registration> registrations = new LinkedHashMap<>();
    private boolean closed;
    public NativeEpoll(int descriptor) { this.descriptor = descriptor; }

    public final class Registration implements AutoCloseable {
        private final Object key;
        private final int descriptor;
        private final Consumer<Registration> detach;
        private boolean closed;
        Registration(Object key, int descriptor, Consumer<Registration> detach) {
            this.key = key; this.descriptor = descriptor; this.detach = detach;
        }
        public Object getKey() { return key; }
        public int getDescriptor() { return descriptor; }
        void finishDeleted() {
            if (closed) return;
            closed = true;
            registrations.remove(key);
            try { NativePollApi.close(descriptor); }
            finally { detach.accept(this); }
        }
        @Override public void close() {
            synchronized (NativeEpoll.this) {
                if (!closed) {
                    try {
                        if (!NativeEpoll.this.closed) Api.control(NativeEpoll.this.descriptor, 2, descriptor, null);
                    } finally { finishDeleted(); }
                }
            }
        }
    }

    public synchronized void control(int operation, Object key, NativeFileResource target, byte[] event,
                                      Consumer<Registration> attach, Consumer<Registration> detach) {
        if (closed) throw propagate(new NativeFileException("epoll_ctl", 9));
        if (operation != 1 && operation != 2 && operation != 3) throw propagate(new NativeFileException("epoll_ctl", 22));
        var existing = registrations.get(key);
        if (existing != null) {
            // A duplicate ADD still obtains EEXIST from the kernel.
            Api.control(descriptor, operation, existing.descriptor, event);
            if (operation == 2) existing.finishDeleted();
            return;
        }
        if (operation != 1) throw propagate(new NativeFileException("epoll_ctl", 2));
        int duplicate = target.duplicateDescriptor();
        boolean registered = false;
        var registration = new Registration(key, duplicate, detach);
        try {
            Api.control(descriptor, operation, duplicate, event);
            registered = true;
            registrations.put(key, registration);
            attach.accept(registration);
        } catch (Throwable failure) {
            try { if (registered) registration.close(); else NativePollApi.close(duplicate); }
            catch (Throwable closing) { failure.addSuppressed(closing); }
            throw propagate(failure);
        }
    }

    public synchronized int ready(ManagedAddress destination, int maximum) {
        if (closed) throw propagate(new NativeFileException("epoll_wait", 9));
        return Api.ready(descriptor, destination, maximum);
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        Throwable failure = null;
        for (var registration : new ArrayList<>(registrations.values())) {
            try { registration.close(); }
            catch (Throwable error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
        }
        try { NativePollApi.close(descriptor); }
        catch (Throwable error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
        if (failure != null) throw propagate(failure);
    }

    /** native-file-api.c checks the image ABI; event data remains opaque bytes. */
    private static final class Api {
        private static final Linker LINKER = Linker.nativeLinker();
        private static final MemoryLayout CAPTURE = Linker.Option.captureStateLayout();
        private static final long ERRNO_OFFSET = CAPTURE.byteOffset(MemoryLayout.PathElement.groupElement("errno"));
        private static final MethodHandle CTL = LINKER.downcallHandle(LINKER.defaultLookup().find("epoll_ctl").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT, ValueLayout.ADDRESS), Linker.Option.captureCallState("errno"));
        private static final MethodHandle WAIT = LINKER.downcallHandle(LINKER.defaultLookup().find("epoll_wait").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT), Linker.Option.captureCallState("errno"));

        static void control(int descriptor, int operation, int target, byte[] event) {
            try (var arena = Arena.ofConfined()) {
                var errors = arena.allocate(CAPTURE);
                MemorySegment image;
                if (event == null) image = MemorySegment.NULL;
                else {
                    image = arena.allocate(12, 8);
                    image.copyFrom(MemorySegment.ofArray(event));
                }
                if ((int) CTL.invokeExact(errors, descriptor, operation, target, image) < 0)
                    throw new NativeFileException("epoll_ctl", errors.get(ValueLayout.JAVA_INT, ERRNO_OFFSET));
            } catch (Throwable failure) { throw propagate(failure); }
        }

        static int ready(int descriptor, ManagedAddress destination, int maximum) {
            try (var arena = Arena.ofConfined()) {
                var errors = arena.allocate(CAPTURE);
                var image = arena.allocate(maximum * 12L, 8);
                int count = (int) WAIT.invokeExact(errors, descriptor, image, maximum, 0);
                if (count < 0) throw new NativeFileException("epoll_wait", errors.get(ValueLayout.JAVA_INT, ERRNO_OFFSET));
                for (int index = 0; index < count * 12; index++)
                    destination.writeWord8(index, image.get(ValueLayout.JAVA_BYTE, index));
                return count;
            } catch (Throwable failure) { throw propagate(failure); }
        }
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException propagate(Throwable failure) throws E { throw (E) failure; }
}
