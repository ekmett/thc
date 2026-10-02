# Terminal images and saved pointers

THC's terminal operations use the build host's checked `<termios.h>` and
`<signal.h>` layout. Image access validates the complete caller-owned byte
region, including writable storage before mutation. Pointer cells in the image
are rejected. The `c_cc` view retains the original allocation.

Ordinary `__hscore_*` image accessors and header constants use package-declared
native linkage. The `TermiosAbiTest` suite checks THC's internal image handling;
it does not establish that an unlinked foreign declaration can execute.

The original `__hscore_get_saved_termios` and `__hscore_set_saved_termios`
operations remain explicit overrides because their saved pointers belong to the
THC context. Their native fixture checks pointer retention and replacement,
including offsets, without inspecting the pointed-to bytes. Each context owns
its saved roots and releases them at disposal.

Image access does not grant terminal or signal-mask authority. Those operations
require their separately admitted runtime boundaries. Layouts from different
platforms are not interchangeable.
