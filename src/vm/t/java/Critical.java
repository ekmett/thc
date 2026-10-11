// SPDX-FileCopyrightText: 2026 Edward Kmett <ekmett@gmail.com>
// SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0
final class Critical {
  static { System.loadLibrary("jam_jni"); }
  static native boolean entered();
  static native void hold(int[] array);
}
