#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Validated native/Core fixture cache, never a cache of JVM test outcomes.

Only trusted-main workflows may publish these bundles. Hashes establish integrity
and freshness, not producer authenticity. Original preparation provenance is
copied verbatim. Workspace paths intentionally participate in the key.
"""
import argparse
import ast
import gzip
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import platform
import re
import shutil
import stat
import subprocess
import sys
import tarfile
import zlib

SCHEMA = 1
HEX = re.compile(r"[0-9a-f]{64}\Z")
SELF = ".github/scripts/fast_inputs.py"
COMPILER_BUILD_INPUTS = ("thc.cabal", "cabal.project", "Setup.hs", "Makefile")
WIRED_SOURCE = "src/THC/Driver/Wired.hs"
# These are the runtime files actually fingerprinted by prepare-tests.sh's
# preparers. An additional recorded runtime source fails closed until reviewed.
RUNTIME_INPUTS = ("src/main/kotlin/thc/runtime/VectorMemoryPrimitives.kt",
                  "src/main/java/thc/runtime/DoubleX2.java")
MANIFEST_DIRS = """address-fields array-slices bignat-literals bit-primops
boxed-arrays boxed-array-extensions bytearray compare-byte-arrays data-to-tag double-arrays
explicit64-primops float-word-arrays fused-floating int-arrays int16-arrays int32-arrays
int8-arrays integer-primops managed-address-reads mutable-bytearray-size mutable-bytearrays mutvar
narrow-literal-proofs original-stack original-stack-formatter original-stdio original-stdio-read original-handle-readiness resize-bytearrays scalar-bitcasts short-bytes-slices sqrt
