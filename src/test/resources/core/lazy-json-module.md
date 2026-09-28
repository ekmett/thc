# Indexed JSON runtime control

`lazy-json-module.json` is a handwritten synthetic control, not GHC-exported Core
or a native benchmark. Its entry selects two cold scalar functions and leaves an
unsupported definition unreachable. Expected results are the independent scalar
expressions `x + 1` for zero and `x + 2` otherwise.

The runtime test builds navigation from the unchanged JSON bytes and requires
lazy body admission. Serialized `.idx` sidecars have been retired.

Source SHA-256: `0d9dacecb7b3e8b617e01f7eca52a5017aba4a922d994c79f89416229841b1b6`
