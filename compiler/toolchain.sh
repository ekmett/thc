# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

# Shared by the compiler drivers; executable paths may contain spaces. Resolve
# the companion package manager from the selected compiler, not from PATH.
selected_ghc=${GHC:-ghc}
ghc_command=$(command -v "$selected_ghc") || {
  echo "Selected GHC executable not found: $selected_ghc" >&2
  exit 1
}
GHC=$(realpath "$ghc_command") || exit 1
version=$("$GHC" --numeric-version)
if [ -n "${GHC_PKG:-}" ]; then
  pkg_command=$(command -v "$GHC_PKG") || {
    echo "Selected ghc-pkg executable not found: $GHC_PKG" >&2
    exit 1
  }
else
  ghc_bin=$(dirname "$GHC")
  if [ -x "$ghc_bin/ghc-pkg-$version" ]; then
    pkg_command=$ghc_bin/ghc-pkg-$version
  elif [ -x "$ghc_bin/ghc-pkg" ]; then
    pkg_command=$ghc_bin/ghc-pkg
  else
    echo "No ghc-pkg beside selected GHC $GHC; set GHC_PKG explicitly" >&2
    exit 1
  fi
fi
GHC_PKG=$(realpath "$pkg_command") || exit 1
pkg_version=$("$GHC_PKG" --version)
if [ "$version" != 9.14.1 ] || [ "$pkg_version" != 'GHC package manager version 9.14.1' ]; then
  echo "THC requires GHC and ghc-pkg 9.14.1; found $version / $pkg_version" >&2
  exit 1
fi
ghc_db=$("$GHC" --print-global-package-db)
if ! pkg_listing=$("$GHC_PKG" --global --no-user-package-db list ghc-internal); then
  echo "Selected ghc-pkg could not list ghc-internal in its global package database" >&2
  exit 1
fi
pkg_db=$(printf '%s\n' "$pkg_listing" | sed -n '1p')
if [ -z "$ghc_db" ] || [ -z "$pkg_db" ]; then
  echo "Selected GHC and ghc-pkg did not report global package databases" >&2
  exit 1
fi
ghc_db=$(realpath "$ghc_db") || exit 1
pkg_db=$(realpath "$pkg_db") || exit 1
if [ "$ghc_db" != "$pkg_db" ]; then
  echo "Selected GHC and ghc-pkg use different global package databases: $ghc_db / $pkg_db" >&2
  exit 1
fi
export GHC GHC_PKG
