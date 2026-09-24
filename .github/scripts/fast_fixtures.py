# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Prepare only native fixtures needed by selected JUnit classes.

The persistent stamps are local acceleration hints. Every reuse checks both the
declared source bytes and every output byte; an unrecognised class runs the
complete preparation script instead of assuming it has no native inputs.
"""

import hashlib
import json
from pathlib import Path
import re
import stat
import sys

import fast_inputs


MANIFEST = Path(".github/scripts/fast-fixtures.json")
STAMP_DIR = Path("build/fast/fixtures")
FULL_STAMP = STAMP_DIR / "full.json"
# The shebang and non-comment command body of reviewed prepare-tests.sh. A new
# preparation command disables reuse until its output scope is reviewed.
FULL_PREPARATION_PLAN = "be0529f89468422684e730c7cef65fe059e0b2e85c8f0de7ce619a6b4a3de547"
FULL_OUTPUT_ROOTS = frozenset(f"build/{name}" for name in fast_inputs.BUILD_DIRS) | frozenset({
    "build/addr-identity", "build/io-main-pap", "build/managed-mvars", "build/managed-md5-native",
    "build/pinned-addresses", "build/pinned-pointer-cells", "build/simd-capability-smoke", "build/managed-address-reads",
    "build/original-stdio", "build/core-continuation", "build/live-async", "build/thread-async", "build/small-arrays", "build/floating-address",
    "build/floating-byte-offset", "build/narrow-byte-offset", "build/int32-byte-offset",
