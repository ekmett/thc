// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0

import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import sun.misc.Unsafe;

/** Exercise the packaged queue itself, without executing Native Image VM operations. */
public final class QueueLifecycleTest {
    static final Class<?> OP = type("JavaVMOperation");
    static final Class<?> QUEUE = type("VMOperationControl$JavaVMOperationQueue");
    static final Class<?> ELEMENT = type("PlatformThreads$GetAllThreadsOperation");
    static final Unsafe U;
    static {
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            U = (Unsafe) f.get(null);
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    static Class<?> type(String name) {
        try { return Class.forName("com.oracle.svm.core.thread." + name); }
        catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    static Object queue() throws Exception {
        Constructor<?> c = QUEUE.getDeclaredConstructor(String.class);
        c.setAccessible(true);
        return c.newInstance("queue lifecycle test");
    }
    static Object element() throws Exception {
        // Its real constructor enters VM-only machinery; queue tests need only next and payload.
        return U.allocateInstance(ELEMENT);
    }
    static Object field(Object o, Class<?> owner, String name) throws Exception {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }
    static Object invoke(Object q, String name, Object... args) throws Exception {
        Class<?>[] types = new Class<?>[args.length];
        java.util.Arrays.fill(types, OP);
        Method m = QUEUE.getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(q, args);
    }
    static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    static void empty(Object q) throws Exception {
        check((boolean) invoke(q, "isEmpty"), "queue is empty");
        check(field(q, QUEUE, "head") == null, "empty head");
        check(field(q, QUEUE, "tail") == null, "empty queue retains its last operation");
    }
    static void detached(Object op) throws Exception {
        check(field(op, OP, "next") == null, "removed operation retains its successor");
    }
    static WeakReference<Object> releasedPayload(Object q) throws Exception {
        Object op = element(), payload = new Object();
        ArrayList<Object> result = new ArrayList<>();
        result.add(payload);
        Field f = ELEMENT.getDeclaredField("result");
        f.setAccessible(true);
        f.set(op, result);
        invoke(q, "push", op);
        check(invoke(q, "pop") == op, "payload operation returned");
        return new WeakReference<>(payload);
    }
    public static void main(String[] args) throws Exception {
        Object q = queue();
        check(invoke(q, "pop") == null, "empty pop");
        empty(q);
        Object a = element(), b = element(), c = element(), d = element();
        invoke(q, "push", a);
        check(invoke(q, "pop") == a, "singleton pop");
        detached(a); empty(q);
        for (Object op : new Object[]{a,b,c}) invoke(q, "push", op);
        invoke(q, "remove", a, b);
        detached(b);
        invoke(q, "remove", a, c);
        detached(c);
        invoke(q, "push", d);
        check(invoke(q, "pop") == a && invoke(q, "pop") == d, "append after tail removal");
        empty(q);
        invoke(q, "push", a); invoke(q, "push", b);
        invoke(q, "remove", null, a); detached(a);
        check(invoke(q, "peek") == b, "remove head");
        invoke(q, "remove", null, b); detached(b); empty(q);
        for (Object op : new Object[]{a,b,c}) invoke(q, "push", op);
        for (Object op : new Object[]{a,b,c}) check(invoke(q, "pop") == op, "FIFO after reuse");
        empty(q);
        WeakReference<Object> payload = releasedPayload(q);
        empty(q);
        for (int i = 0; i != 20 && !payload.refersTo(null); ++i) {
            System.gc(); Thread.sleep(10);
        }
        check(payload.refersTo(null), "empty queue retains completed operation payload");
        Reference.reachabilityFence(q);
        System.out.println("VM operation queue lifecycle passed: removal, reuse, FIFO, payload release");
    }
}
