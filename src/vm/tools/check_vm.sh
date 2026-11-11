#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Edward Kmett <ekmett@gmail.com>
# SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0
set -euo pipefail
cd "$(dirname "$0")/.."
exec "${JAM_PYTHON:-python3}" tools/check_vm.py
