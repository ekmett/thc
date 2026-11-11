// SPDX-FileCopyrightText: 2026 Edward Kmett <ekmett@gmail.com>
// SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0
import java.lang.ref.*;

public class WeakSmoke {
  static volatile Object resurrected;
  static int finalized;
  static class Value { final Object edge; Value(Object edge) { this.edge = edge; } }
  static class Capture implements Runnable {
    final Object key;
    Capture(Object key) { this.key = key; }
    public void run() { System.gc(); resurrected = key; ++finalized; }
  }
  record Chain(Object root, long first, long second) {}
  record Dead(long first, long second, WeakReference<Object> javaWeak) {}
  static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
  static Chain chain() {
    Object k1 = new Object(), k2 = new Object();
    long second = THCWeak.create(k2, new Value("second"), null);
    long first = THCWeak.create(k1, new Value(k2), null);
    return new Chain(k1, first, second);
  }
  static long selfSupported() {
    Object k = new Object();
    return THCWeak.create(k, new Value(k), null);
  }
  static Dead deadBatch() {
    Object k = new Object();
    long a = THCWeak.create(k, new Object(), new Capture(k));
    long b = THCWeak.create(k, new Object(), (Runnable) () -> ++finalized);
    return new Dead(a, b, new WeakReference<>(k));
  }
  static void garbage() { HeapSmoke.garbage(1000); System.gc(); }
  public static void main(String[] args) {
    Chain chain = chain();
    for (int i = 0; i != 8; ++i) {
      garbage();
      check(THCWeak.deref(chain.first()) instanceof Value, "live first association");
      check(((Value) THCWeak.deref(chain.second())).edge.equals("second"), "reverse-order fixed point");
    }
    Reference.reachabilityFence(chain.root());
    long dead = selfSupported();
    Dead batch = deadBatch();
    garbage();
    check(THCWeak.deref(dead) == null, "V -> K cannot self-support");
    check(THCWeak.deref(batch.first()) == null && THCWeak.deref(batch.second()) == null,
          "entire same-key batch retired before finalizer marking");
    check(batch.javaWeak().get() == null, "Java weak clearing precedes Haskell finalizer resurrection");
    THCWeak.runFinalizers();
    check(finalized == 2 && resurrected != null, "both finalizers run; captured key survives nested GC");
    garbage();
    check(THCWeak.deref(batch.first()) == null, "resurrection cannot rearm dead association");
    THCWeak.runFinalizers();
    check(finalized == 2, "at-most-once finalization");
    Object explicitKey = new Object();
    long explicit = THCWeak.create(explicitKey, new Object(), (Runnable) () -> ++finalized);
    THCWeak.runFinalizer(explicit);
    THCWeak.runFinalizer(explicit);
    check(finalized == 3 && THCWeak.deref(explicit) == null, "explicit finalization is idempotent");
    Reference.reachabilityFence(explicitKey);
    resurrected = null;
    garbage();
    System.out.println("HotSpot jam Java/GHC generalized weak and finalization checks passed");
  }
}
