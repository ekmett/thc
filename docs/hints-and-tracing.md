<!-- SPDX-FileCopyrightText: 2026 Edward Kmett -->
<!-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause -->

# Prefetch hints and trace events

Both backends implement all sixteen GHC 9.14.1 prefetch primops: locality
levels 0–3 for `ByteArray#`, `MutableByteArray#`, `Addr#`, and lifted values.
They are no-op performance hints on the JVM. They preserve ordinary argument
and state sequencing, never force a lifted value, and do not dereference or
bounds-check an ignored address. They do not promise a hardware cache effect.

`traceEvent#`, `traceMarker#`, and `traceBinaryEvent#` emit records to the THC
context's existing stderr diagnostic stream, which an embedding can redirect.
Text events and markers read NUL-terminated byte strings. Binary events read
exactly the supplied nonnegative byte count, including embedded NUL bytes.
Zero-length binary events do not read their address.

```text
[thc trace event] starting
[thc trace marker] finished
[thc trace binary] 41004200
```

Text retains UTF-8 bytes; backslashes and ASCII control characters are escaped
so each event occupies one line. Binary payloads use lossless lowercase hex.
Writes are serialized per context output stream and flushed. Invalid or expired
read regions fail before publishing a record. Existing supported native storage
uses its context/lifetime borrow; unowned numeric pointers cannot be read.
Stream errors follow the existing void RTS diagnostic hooks and are ignored.

This is a useful JVM diagnostic translation, **not** GHC's `.eventlog` format,
RTS event-selection flags, timestamps, or eventlog tooling integration. All 19
operations count as implemented for this target; hardware prefetch and GHC
eventlog serialization are not claimed.

`compiler/test-fixtures/HintTraceAudit.hs` is a runnable example. Its Haskell
producer checks native return values and actual native user-event payloads;
Kotlin checks the JVM record format, lazy bottom hints, unchanged memory, byte
bounds, native lifetime/context checks, concurrent records, and first compiled
calls on both backends.

```sh
cabal run exe:thc-fixtures --offline -- hint-trace
./gradlew --no-daemon test --tests thc.runtime.HintTraceTest
JAVA_TOOL_OPTIONS=-Dthc.handoffSlabs=true ./gradlew --no-daemon test \
  --tests thc.runtime.HintTraceTest --rerun
```
