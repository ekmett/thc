// SPDX-FileCopyrightText: 2026 Edward Kmett <ekmett@gmail.com>
// SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0
package com.oracle.svm.core.jam;

import org.graalvm.nativeimage.Isolate;
import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.c.struct.RawField;
import org.graalvm.nativeimage.c.struct.RawStructure;
import org.graalvm.word.PointerBase;

/** Native stack storage read before establishing the scanner's reserved registers. */
@RawStructure
interface JamScanContext extends PointerBase {
    @RawField Isolate getIsolate();
    @RawField void setIsolate(Isolate value);
    @RawField IsolateThread getThread();
    @RawField void setThread(IsolateThread value);
}
