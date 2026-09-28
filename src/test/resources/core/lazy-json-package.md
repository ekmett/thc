# Indexed package runtime fixture

This handwritten synthetic scalar module adds the package boundary header to
the separate `lazy-json-module.json` control. It is not exported GHC Core or a
native performance oracle. Expected results are independently specified as
`x + 1` for zero and `x + 2` otherwise. The fourth body is deliberately unusable
and must remain unprepared. The original loose-file fixture is unchanged.

Tests build in-memory navigation from the unchanged JSON. Serialized `.idx`
sidecars have been retired.

JSON 1750 bytes SHA-256:
`3a117a5160dab7c707765f02b506bcb0003e06f8718e73e915aa9fe274a47700`.

Tests construct loose, ordered and reordered ZIP framing from these exact
bytes, then exercise both backends. These framing controls do not substitute
for the separate producer-export integration tests.
