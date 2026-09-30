-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- |
-- Module      : THC.Driver.PinnedFlags
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Haskell 2010
--
-- Select source-package flags from the selected stock compiler's evidence.
module THC.Driver.PinnedFlags (pinnedLibraryFlags) where

-- | Package name, exact registered owned modules, and selected GHC @--info@.
-- The caller must still require complete module and dependency equality after
-- Cabal configuration. This only selects flags with known upstream evidence.
pinnedLibraryFlags :: String -> [String] -> [(String, String)] -> Either String [String]
pinnedLibraryFlags "text" modules _
  | all (`elem` modules) ["Data.Text", "Data.Text.Internal.Validate", "Data.Text.Internal.Validate.Native"] =
      Right [if "Data.Text.Internal.Validate.Simd" `elem` modules then "-fsimdutf" else "-f-simdutf"]
  | otherwise = Left "text pinned flags: incomplete registered validation-module inventory"
pinnedLibraryFlags "haskeline" _ _ = Right ["-f-examples"]
pinnedLibraryFlags package _ info | package == "ghc" || package == "ghci" =
  case (lookup "Have interpreter" info, lookup "Use interpreter" info) of
    (Just "YES", Just "YES") -> Right (compilerFlags "-finternal-interpreter")
    (Just "NO", Just "NO") -> Right (compilerFlags "-f-internal-interpreter")
    observed -> Left (package ++ " pinned flags: missing, inconsistent or unknown interpreter evidence: " ++ show observed)
  where
    -- Hadrian Settings/Packages.hs disables build-tool-depends for the shipped
    -- parser sources. The upstream custom setup generators are supplied separately.
    compilerFlags interpreter =
      interpreter : ["-f-build-tool-depends" | package == "ghc"]
pinnedLibraryFlags _ _ _ = Right []
