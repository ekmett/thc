#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"
sources=()
while IFS= read -r -d '' source; do
  sources+=("$source")
done < <(git ls-files -z -- '*.hs' '*.lhs')
if [ "${#sources[@]}" -eq 0 ]; then
  printf '%s\n' 'No tracked Haskell sources found.' >&2
  exit 2
fi
# An explicit array preserves filenames and selects only tracked files. HLint's
# --git option also discovers untracked files in some versions.
exec "${HLINT:-hlint}" -j2 "${sources[@]}" "$@"
