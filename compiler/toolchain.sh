# Shared by the compiler drivers; executable paths may contain spaces.
GHC=${GHC:-ghc}
GHC_PKG=${GHC_PKG:-ghc-pkg}
export GHC GHC_PKG
version=$("$GHC" --numeric-version)
pkg_version=$("$GHC_PKG" --version)
if [ "$version" != 9.14.1 ] || [ "$pkg_version" != 'GHC package manager version 9.14.1' ]; then
  echo "THC requires GHC and ghc-pkg 9.14.1; found $version / $pkg_version" >&2
  exit 1
fi
