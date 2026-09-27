// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import thc.Json;

import static org.junit.jupiter.api.Assertions.*;

@EnabledOnOs(OS.WINDOWS)
class WindowsAbiInitializationTest {
    @Test void failedReceiptReadsValidationAndCloseRemainRetryableWithoutWrapping() throws Exception {
        byte[] receipt;
        try (var stream = getClass().getResourceAsStream("/thc/native/windows-directory-abi.json")) { receipt = stream.readAllBytes(); }
        var layout = (Map<?, ?>) ((Map<?, ?>) Json.INSTANCE.parse(new String(receipt, java.nio.charset.StandardCharsets.UTF_8))).get("layout");
        for (var service : List.of("WindowsDirectoryStreams", "WindowsCodePages")) for (int failureMode = 0; failureMode < 3; failureMode++) {
            var original = new IOException("receipt failure");
            var reads = new AtomicInteger();
            int mode = failureMode;
            String name = "thc.runtime." + service;
            // Isolate the service and its nest to exercise fresh lazy state;
            // the successful attempt still reads the actual native probe receipt.
            var loader = new ClassLoader(getClass().getClassLoader()) {
                @Override protected Class<?> loadClass(String requested, boolean resolve) throws ClassNotFoundException {
                    if (!requested.equals(name) && !requested.startsWith(name + "$")) return super.loadClass(requested, resolve);
                    synchronized (getClassLoadingLock(requested)) {
                        var result = findLoadedClass(requested);
                        if (result == null) {
                            try (var stream = getParent().getResourceAsStream(requested.replace('.', '/') + ".class")) {
                                byte[] bytes = stream.readAllBytes();
                                result = defineClass(requested, bytes, 0, bytes.length);
                            } catch (IOException failure) { throw new ClassNotFoundException(requested, failure); }
                        }
                        if (resolve) resolveClass(result);
                        return result;
                    }
                }
                @Override public InputStream getResourceAsStream(String path) {
                    assertEquals("thc/native/windows-directory-abi.json", path);
                    boolean first = reads.getAndIncrement() == 0;
                    if (!first) return new ByteArrayInputStream(receipt);
                    if (mode == 2) return new ByteArrayInputStream("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    return new InputStream() {
                        private final InputStream data = new ByteArrayInputStream(receipt);
                        @Override public int read() throws IOException { if (mode == 0) throw original; return data.read(); }
                        @Override public void close() throws IOException { if (mode == 1) throw original; }
                    };
                }
            };
            var abi = loader.loadClass(name).getField("Abi").get(null);
            assertEquals(0, reads.get(), "service construction must not eagerly load the ABI");
            boolean directory = service.equals("WindowsDirectoryStreams");
            var size = abi.getClass().getMethod(directory ? "getSize" : "getInfoBytes");
            var failure = assertThrows(InvocationTargetException.class, () -> size.invoke(abi)).getCause();
            if (mode == 2) assertInstanceOf(RuntimeFault.class, failure); else assertSame(original, failure);
            assertEquals(layout.get(directory ? "findDataBytes" : "cpInfoBytes"), size.invoke(abi));
            assertEquals(2, reads.get());
            size.invoke(abi);
            assertEquals(2, reads.get(), "a successfully published ABI is cached");
        }
    }
}
