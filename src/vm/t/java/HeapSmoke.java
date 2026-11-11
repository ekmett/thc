// SPDX-FileCopyrightText: 2026 Edward Kmett <ekmett@gmail.com>
// SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0
import java.lang.ref.*;
import java.util.concurrent.atomic.AtomicReference;

public class HeapSmoke {
  static volatile Object sink;
  static class Node {
    final long id;
    Node next, self;
    Node(long id, Node next) { this.id = id; this.next = next; self = this; }
  }
  static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
  static long walk(Node node) {
    long sum = 0;
    for (; node != null; node = node.next) {
      check(node.self == node, "self edge");
      sum += node.id;
    }
    return sum;
  }
  static void garbage(int iterations) {
    for (int i = 0; i < iterations; ++i) sink = new byte[8192 + (i % 127) * 8];
    sink = null;
  }
  static WeakReference<Object> weak(ReferenceQueue<Object> queue) {
    return new WeakReference<>(new Object(), queue);
  }
  static PhantomReference<Object> phantom(ReferenceQueue<Object> queue) {
    return new PhantomReference<>(new Object(), queue);
  }
  static void await(ReferenceQueue<Object> queue, Reference<?> expected) throws Exception {
    for (int i = 0; i != 40; ++i) {
      System.gc();
      if (queue.remove(25) == expected) return;
    }
    throw new AssertionError("reference never enqueued: " + expected.getClass());
  }
  public static void main(String[] args) throws Exception {
    if (args.length != 0 && args[0].equals("allocation")) {
      garbage(40000);
      System.out.println("allocation smoke passed");
      return;
    }
    Node root = null;
    for (int i = 0; i != 500; ++i) root = new Node(i, root);
    int hash = System.identityHashCode(root);
    for (int i = 0; i != 30000; ++i) check(walk(root) == 124750, "compiled graph walk");
    for (int epoch = 0; epoch != 15; ++epoch) {
      synchronized (root) {
        garbage(3000);
        System.gc();
        check(walk(root) == 124750, "retained graph after movement");
        check(System.identityHashCode(root) == hash, "identity hash and monitor");
      }
    }
    ReferenceQueue<Object> wq = new ReferenceQueue<>();
    WeakReference<Object> weak = weak(wq);
    await(wq, weak);
    check(weak.get() == null, "weak referent cleared");
    ReferenceQueue<Object> pq = new ReferenceQueue<>();
    PhantomReference<Object> phantom = phantom(pq);
    await(pq, phantom);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread[] workers = new Thread[3];
    for (int i = 0; i != workers.length; ++i) {
      workers[i] = new Thread(() -> { try { garbage(6000); } catch (Throwable t) { failure.set(t); } });
      workers[i].start();
    }
    for (Thread t : workers) t.join();
    if (failure.get() != null) throw new AssertionError("concurrent allocation", failure.get());
    check(walk(root) == 124750, "root survives concurrent allocation");
    System.out.println("HotSpot jam graph, JIT, monitor, Java references and allocation checks passed");
  }
}
