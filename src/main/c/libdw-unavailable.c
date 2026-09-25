// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

// Compile the original configured RTS bodies, including its actual finalizer
// symbols. No replacement algorithm or enabled native DWARF backend is supplied.
#include "Rts.h"
#if USE_LIBDW
#error "THC's unavailable DWARF provider requires the selected GHC USE_LIBDW=0 profile"
#endif
#include "../../../compiler/pinned-ghc-rts/Libdw.c"
#include "../../../compiler/pinned-ghc-rts/LibdwPool.c"
