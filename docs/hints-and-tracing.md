<!-- SPDX-FileCopyrightText: 2026 Edward Kmett -->
<!-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause -->

# Prefetch hints and trace events

Both backends implement all sixteen GHC 9.14.1 prefetch primops: locality
levels 0–3 for `ByteArray#`, `MutableByteArray#`, `Addr#`, and lifted values.
They are no-op performance hints on the JVM. They preserve ordinary argument
and state sequencing, never force a lifted value, and do not dereference or
bounds-check an ignored address. They do not promise a hardware cache effect.

`traceEvent#`, `traceMarker#`, and `traceBinaryEvent#` start disabled. Select
`THC.Trace.setTraceSink TraceStderr`, `TraceJFR`, or `TraceStderrAndJFR` to enable
them in the current context. Hosts can use the same existing
`Language.currentState().getRuntimeTrace().control(500, sink)` control inside an
entered THC context (sink values 0–3 follow that order, starting with off).
Enabling invalidates the context's initial disabled Graal assumption, so guest
code compiled while off starts emitting on its next call. Disabling silences
subsequent calls and re-enabling uses the current sink; other contexts on a shared
engine keep their own configuration. Disabled primops preserve argument and state
sequencing but do not read or validate the payload region or binary length.

When enabled, text events and markers read NUL-terminated byte strings. Binary
events read exactly the supplied count in `0..Int.MAX_VALUE`, including embedded
NUL bytes. Zero-length binary events do not read their address.

The original `GHC.Internal.RTS.Flags.Test.getUserEventTracingEnabled` getter is
supported when the selected GHC 9.14.1 producer supplies schema-2 RTS layout
metadata from its headers. Its `RtsFlags.TraceFlags.user` one-byte CBool reports
whether a context sink is selected, including JFR without an active recording. It does not follow the separate `thc.diagnostics` counter-report switch.
The context-owned address supports composed pointer offsets and only this
one-byte field read; other fields, widths, writes and numeric pointer projection
are rejected. It is not a zero-filled native RTS structure. The automatically
extracted struct sizes, nested offsets and widths are bound to the selected
compiler version, ABI, target and way; one context cannot replace that producer
layout after publishing its RTS view. Legacy schema-1 layouts explicitly lack
RTS flag support, with no guessed offset fallback. Supported compiler/decoder
contracts still apply; compatible sizes alone do not admit another GHC version.
Native macOS and Windows getter qualification remains separate from Linux
header measurements and synthetic codec controls.

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

JFR uses the existing `thc.RuntimeTrace` event with context identity and phases
`event`, `marker`, and `binary`, zero span ID and elapsed duration, and JFR's own
timestamp and emitter thread. `message` contains UTF-8 display text (malformed
sequences become U+FFFD) or the binary payload's lowercase hex. `payloadHex`
retains the exact bytes for all three original primops, excluding the text
terminator; it is empty for structured `THC.Trace` events. No structured trace
UTF-8 validation or 1 MiB label limit is imposed on the original primops.

Selecting JFR neither starts a recording nor enables an event in existing
recordings. With no recording consuming `thc.RuntimeTrace`, JFR emission is
ignored; enabling that event in a recording later allows subsequent emissions.
A both-sinks selection still emits stderr records in that case. Original primops
return only the state token, so JFR availability/emission status is not returned.

This translation does not implement GHC's `.eventlog` format, RTS event-selection
flags, or eventlog tooling integration. The compiled disabled path has not been
qualified with compiler graphs, so no zero-cost claim is made. All 19
operations count as implemented for this target; hardware prefetch and GHC
eventlog serialization are not claimed.
