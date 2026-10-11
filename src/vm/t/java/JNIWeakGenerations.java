// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0

public final class JNIWeakGenerations {
    public static void main(String[] args) throws InterruptedException {
        Runnable minor = () -> JamWeak.minor(false);
        Runnable promote = () -> JamWeak.minor(true);
        JNIWeakSmoke.run(minor, promote, System::gc);
        JNIWeakSmoke.oldReferent(promote, minor, System::gc);
        System.out.println("JNI weak globals passed: retaining minor, promotion and major");
    }
}
