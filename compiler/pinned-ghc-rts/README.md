# Original GHC RTS source

These seven source/header files are unchanged copies from GHC commit
`902339d332fb4ce2b3c87dcac1ee6495d41ad886` (GHC 9.14.1), under the included
GHC BSD license. The C build verifies their SHA-256 hashes.

`src/main/c/libdw-unavailable.c` compiles the original `USE_LIBDW=0` branches
against the selected GHC 9.14.1 public RTS headers. It rejects a configuration
with libdw enabled. The private headers preserve GHC's declarations and
visibility annotations; they do not replace public RTS types or layouts.

The resulting Sulong resource includes the genuine `libdwPoolRelease` and
`backtraceFree` function symbols. Both bodies are empty in this selected
upstream profile. Enabled DWARF session allocation, unwinding, pool ownership,
and backtrace freeing are not implemented by this provider.
