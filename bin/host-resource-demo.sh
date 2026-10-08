#!/bin/sh
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
set -eu
cd "$(dirname "$0")/.."
bin/acquire-polyglot-demo.sh host-resource "$@"
entry=$(cat build/host-resource/entry.txt)
exec ./gradlew hostResourceDemo --console=plain --args="@build/host-resource/packages.json ${entry%.main}.session"
