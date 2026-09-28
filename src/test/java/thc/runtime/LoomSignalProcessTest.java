// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.TruffleSafepoint;
import java.lang.foreign.*;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;

/** Actual process handlers belong only to these isolated JVM children. */
@EnabledOnOs(OS.LINUX)
@Timeout(90)
public class LoomSignalProcessTest {
    @Test public void nativeSignalReaderReleasesTheOnlyHecAndDispatchesBothBackends() throws Exception {
        var directory = Path.of(System.getProperty("thc.projectRoot"), "build/process-signals");
        Files.createDirectories(directory);
        for (String backend : List.of("ast", "bytecode")) {
            var log = Files.createTempFile(directory, "loom-" + backend + "-", ".log");
            var builder = new ProcessBuilder(System.getProperty("java.home") + "/bin/java", "-Xrs",
                "--add-modules=jdk.incubator.vector", "--enable-native-access=ALL-UNNAMED",
                "--add-opens=java.base/java.lang=ALL-UNNAMED", "-cp", System.getProperty("thc.testRuntimeClasspath"),
                LoomSignalProcessTest.class.getName(), backend).redirectErrorStream(true).redirectOutput(log.toFile());
            for (String name : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "LD_PRELOAD")) builder.environment().remove(name);
            builder.environment().put("_JAVA_SR_SIGNUM", "64");
            var child = builder.start();
            try {
                assertTrue(child.waitFor(30, TimeUnit.SECONDS), "signal child timed out: " + log);
                assertEquals(0, child.exitValue(), Files.readString(log));
                assertTrue(Files.readString(log).contains("Loom dispatched eight native signals"), Files.readString(log));
            } finally { if (child.isAlive()) child.destroyForcibly().waitFor(); }
        }
    }

    private static void await(CountDownLatch latch) {
        TruffleSafepoint.setBlockedThreadInterruptible(null, waiting -> assertTrue(waiting.await(10, TimeUnit.SECONDS)), latch);
    }

    public static void main(String[] arguments) throws Throwable {
        assertTrue(ManagedSignals.hasReducedVmSignals()); assertTrue(NativeSignalTransport.userSignalAvailable());
        try (var context = Context.newBuilder("thc").allowCreateThread(true).allowNativeAccess(true)
                .allowExperimentalOptions(true).option("thc.ThreadHosting", "loom").build()) {
            context.initialize("thc"); context.enter();
            try {
                var owner = Language.currentState(); var threads = owner.getThreads(); threads.setCapabilityCount(1);
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var reading = new CountDownLatch(1); var delivered = new CountDownLatch(8);
                var nativeTransport = new NativeSignalTransport();
                var transport = new ProcessSignalTransport() {
                    boolean returned;
                    public Result install(int signal, int action) { return nativeTransport.install(signal, action); }
                    public Event take() {
                        assertTrue(Thread.currentThread().isVirtual());
                        if (returned) delivered.countDown(); returned = false; reading.countDown();
                        var event = nativeTransport.take(); returned = event != null; return event;
                    }
                    public void wake() { nativeTransport.wake(); }
                    public void resetWake() { nativeTransport.resetWake(); }
                    public void close() { nativeTransport.close(); }
                };
                var service = new ManagedSignals(owner, language, true, () -> true, () -> transport);
                service.bind(new ProcessSignalsTest().program(language, arguments[0])); service.authorizeLauncher();
                try {
                    threads.hostEntry(null, () -> {
                        threads.enterCurrent();
                        try { for (int signal : new int[]{1, 2, 3, 10, 12, 15, 24, 25}) assertEquals(-1L, service.install(signal, -4, ManagedAddress.nullAddress())); }
                        finally { threads.leaveCurrent(); }
                        return null;
                    });
                    await(reading);
                    assertEquals(71L, threads.hostEntry(null, () -> {
                        threads.enterCurrent();
                        try { assertTrue(Thread.currentThread().isVirtual()); return 71L; }
                        finally { threads.leaveCurrent(); }
                    }), "pinned native signal read must release the only HEC");
                    var linker = Linker.nativeLinker();
                    var raise = linker.downcallHandle(linker.defaultLookup().find("raise").orElseThrow(), FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
                    for (int signal : new int[]{1, 2, 3, 10, 12, 15, 24, 25}) assertEquals(0, (int) raise.invokeExact(signal));
                    await(delivered); assertEquals(8, owner.getNativeAllocations().liveCount());
                } finally { service.close(); }
                System.out.println("Loom dispatched eight native signals");
            } finally { context.leave(); }
        }
    }
}
