#!/bin/sh
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
set -eu
cd "$(dirname "$0")/.."
bin/acquire-polyglot-demo.sh polyglot "$@"
exec ./gradlew polyglotDemo --console=plain --args="build/polyglot/packages.json $(cat build/polyglot/entry.txt)"
