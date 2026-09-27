// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StdioHostAbiFailureTest {
    @Test void resourceReadAndCloseFailuresRetainTheirOriginalIdentity() throws Exception {
        byte[] definition;
        try (var stream = StdioHostAbi.class.getResourceAsStream("StdioHostAbi.class")) {
            definition = stream.readAllBytes();
        }
        for (boolean failRead : List.of(false, true)) {
            var readFailure = new IOException("receipt read failure");
            var closeFailure = new IOException("receipt close failure");
            // Isolate only this class to substitute its resource stream, without
            // adding a production injection point or replacing the JSON parser.
            var copy = new ClassLoader(StdioHostAbi.class.getClassLoader()) {
                Class<?> define() { return defineClass(StdioHostAbi.class.getName(), definition, 0, definition.length); }
                @Override public InputStream getResourceAsStream(String name) {
                    assertEquals("thc/native/stdio-host-abi.json", name);
                    return new InputStream() {
                        private final InputStream content = new ByteArrayInputStream("null".getBytes(StandardCharsets.UTF_8));
                        @Override public int read() throws IOException {
                            if (failRead) throw readFailure;
                            return content.read();
                        }
                        @Override public void close() throws IOException { throw closeFailure; }
                    };
                }
            }.define();
            var load = copy.getDeclaredMethod("load");
            load.setAccessible(true);
            var failure = assertThrows(InvocationTargetException.class, () -> load.invoke(null)).getCause();
            assertSame(failRead ? readFailure : closeFailure, failure);
            assertArrayEquals(failRead ? new Throwable[]{closeFailure} : new Throwable[0], failure.getSuppressed());
        }
    }

    @Test void malformedScalarFieldsRejectInDeclarationOrder() {
        // A parser model with valid preceding sections; every value in the
        // section under test is invalid, so rejection must name its first field.
        var errors = new HashMap<String, Object>();
        for (var name : List.of("ENOENT", "EACCES", "EEXIST", "EBADF", "EINVAL", "EIO", "ENOTSUP",
                "EBUSY", "EISDIR", "ENOTTY", "ESPIPE", "EMFILE")) errors.put(name, 1L);
        var open = new HashMap<String, Object>();
        for (var name : List.of("modeBytes", "O_ACCMODE", "O_RDONLY", "O_WRONLY", "O_RDWR", "O_APPEND",
                "O_CREAT", "O_EXCL", "O_BINARY", "O_TRUNC", "O_NOCTTY", "O_NONBLOCK", "F_GETFL", "F_SETFL", "F_SETFD", "FD_CLOEXEC"))
            open.put(name, 0L);
        var receipt = Map.of("schema", 1L, "system", "Linux", "architecture", "x86_64", "target", "x86_64-unknown-linux-gnu",
            "widths", Map.of("charBits", 8L, "pointer", 8L, "int", 4L, "bool", 1L, "size", 8L, "ssize", 8L),
            "errno", errors, "seek", Map.of("SEEK_SET", 0L, "SEEK_CUR", 1L, "SEEK_END", 2L), "open", open);
        for (var section : List.of("errno", "seek", "open")) {
            var wrongFields = new HashMap<>((Map<?, ?>) receipt.get(section));
            wrongFields.replaceAll((key, value) -> null);
            var wrong = new HashMap<>(receipt);
            wrong.put(section, wrongFields);
            String detail = switch (section) {
                case "errno" -> "CInt errno ENOENT";
                case "seek" -> "CInt SEEK_SET";
                default -> "open modeBytes";
            };
            assertEquals("Invalid original stdio host ABI: " + detail,
                assertThrows(RuntimeFault.class, () -> StdioHostAbi.parse(wrong, "Linux", "amd64")).getMessage());
        }
    }
}
