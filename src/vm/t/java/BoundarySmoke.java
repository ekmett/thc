// SPDX-FileCopyrightText: 2026 Edward Kmett <ekmett@gmail.com>
// SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0
import java.lang.ref.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

public class BoundarySmoke {
  static volatile Object resurrected;
  static class Finalizable {
    @SuppressWarnings("removal") protected void finalize() { resurrected = this; }
  }
  static PhantomReference<Object> finalizable(ReferenceQueue<Object> queue) {
    return new PhantomReference<>(new Finalizable(), queue);
  }
  static void softPressure(SoftReference<byte[]> soft) {
    byte[][] retained = new byte[40][];
    long majors = JamWeak.collections(2);
    int n = 0;
    while (JamWeak.collections(2) == majors && n < retained.length) {
      try { retained[n++] = new byte[1024 * 1024]; }
      catch (OutOfMemoryError exhausted) { break; }
    }
    HeapSmoke.check(JamWeak.collections(2) > majors, "retained allocation forces a pressure major");
    HeapSmoke.check(soft.get() == null, "whole-heap allocation pressure clears soft reference");
    Reference.reachabilityFence(retained);
  }
  @SuppressWarnings("removal") public static void main(String[] args) throws Exception {
    int[] pinned = {17};
    Thread critical = new Thread(() -> Critical.hold(pinned));
    critical.start();
    while (!Critical.entered()) Thread.onSpinWait();
    System.gc();
    critical.join();
    HeapSmoke.check(pinned[0] == 18, "JNI critical array survives blocked GC");

    CountDownLatch parked = new CountDownLatch(32), release = new CountDownLatch(1);
    CountDownLatch parkedAgain = new CountDownLatch(32), releaseAgain = new CountDownLatch(1);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread[] threads = new Thread[32];
    for (int i = 0; i != threads.length; ++i) {
      final int id = i;
      threads[i] = Thread.ofVirtual().start(() -> {
        try {
          HeapSmoke.Node root = new HeapSmoke.Node(id, null);
          int hash = System.identityHashCode(root);
          parked.countDown();
          release.await();
          HeapSmoke.check(root.self == root && root.id == id && System.identityHashCode(root) == hash,
                          "virtual-thread stack root survives relocation");
          HeapSmoke.Node next = new HeapSmoke.Node(id + 1000, root);
          parkedAgain.countDown();
          releaseAgain.await();
          HeapSmoke.check(next.self == next && next.id == id + 1000 && next.next == root,
                          "old stack chunk retains newly stored young roots");
        } catch (Throwable t) { failure.set(t); }
      });
    }
    parked.await();
    Thread.sleep(50);
    HeapSmoke.garbage(10000);
    JamWeak.minor(true);
    System.gc();
    release.countDown();
    parkedAgain.await();
    Thread.sleep(50);
    JamWeak.minor(false);
    JamWeak.minor(false);
    releaseAgain.countDown();
    for (Thread thread : threads) thread.join();
    if (failure.get() != null) throw new AssertionError(failure.get());

    SoftReference<byte[]> soft = new SoftReference<>(new byte[2 * 1024 * 1024]);
    softPressure(soft);
    ReferenceQueue<Object> queue = new ReferenceQueue<>();
    PhantomReference<Object> phantom = finalizable(queue);
    for (int i = 0; i != 40 && resurrected == null; ++i) {
      System.gc(); System.runFinalization(); Thread.sleep(10);
    }
    HeapSmoke.check(resurrected != null, "Java finalizer ran and resurrected object");
    HeapSmoke.check(queue.poll() == null, "phantom not enqueued while resurrected");
    resurrected = null;
    HeapSmoke.await(queue, phantom);
    System.out.println("HotSpot jam JNI critical, virtual stack, soft/final/phantom checks passed");
  }
}
