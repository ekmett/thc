-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- |
-- Module      : PinnedFlagsTests
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Haskell 2010; HUnit
--
-- Pure checks for stock-configuration evidence and missing-evidence rejection.
module PinnedFlagsTests (tests) where

import Test.HUnit (Test(..), assertBool, assertEqual, assertFailure)
import THC.Driver.PinnedFlags (pinnedLibraryFlags, pinnedConfigureOptions)
import Data.List (elemIndex, sort)
import THC.Driver.Installed (InstalledUnit(..))
import THC.Driver.Wired (pinnedDependencyOrder)

tests :: Test
tests = TestLabel "pinned library configuration evidence" $ TestList $
  [ TestCase $ assertEqual "native Windows configure selects upstream Windows branches"
      (Right ["--configure-option=--host=x86_64-unknown-mingw32"])
      (pinnedConfigureOptions "mingw32" [("Host platform", "x86_64-unknown-mingw32")])
  , TestCase $ rejects (pinnedConfigureOptions "mingw32" [])
  , TestCase $ rejects (pinnedConfigureOptions "mingw32" [("Host platform", "")])
  , TestCase $ assertEqual "Unix configure arguments remain unchanged" (Right [])
      (pinnedConfigureOptions "linux" [])
  , TestCase $ assertEqual "macOS configure arguments remain unchanged" (Right [])
      (pinnedConfigureOptions "darwin" [])
  , TestCase $ assertEqual "stock native text disables the upstream SIMD default"
      (Right ["-f-simdutf"]) (pinnedLibraryFlags "text" nativeText [])
  , TestCase $ assertEqual "registered SIMD module enables the matching source flag"
      (Right ["-fsimdutf"])
      (pinnedLibraryFlags "text" ("Data.Text.Internal.Validate.Simd" : nativeText) [])
  , TestCase $ rejects (pinnedLibraryFlags "text" [] [])
  , TestCase $ rejects (pinnedLibraryFlags "text" ["Data.Text.Internal.Validate.Simd"] [])
  , TestCase $ assertEqual "unrelated packages retain ordinary Cabal configuration"
      (Right []) (pinnedLibraryFlags "base" [] [])
  , TestCase $ assertEqual "bignum remains the separately verified interface choice"
      (Right []) (pinnedLibraryFlags "ghc-internal" [] [])
  , TestCase $ assertEqual "Haskeline library recipe disables example executables"
      (Right ["-f-examples"]) (pinnedLibraryFlags "haskeline" [] [])
  ] ++
  [ TestCase $ assertEqual (package ++ " interpreter " ++ value)
      (Right (flag : ["-f-build-tool-depends" | package == "ghc"]))
      (pinnedLibraryFlags package [] [("Have interpreter", value), ("Use interpreter", value)])
  | package <- ["ghc", "ghci"], (value, flag) <- [("YES", "-finternal-interpreter"), ("NO", "-f-internal-interpreter")]
  ] ++
  [ TestCase $ rejects (pinnedLibraryFlags package [] info)
  | package <- ["ghc", "ghci"], info <- [[], [("Have interpreter", "YES")], [("Use interpreter", "YES")],
             [("Have interpreter", "YES"), ("Use interpreter", "NO")],
             [("Have interpreter", "NO"), ("Use interpreter", "YES")],
             [("Have interpreter", "unknown"), ("Use interpreter", "unknown")]]
  ] ++
  [ TestCase $ case order [unit "left" ["middle"], unit "right" ["base"],
                           unit "middle" ["base"], unit "base" []] of
      Left message -> assertFailure message
      Right ordered -> do
        assertEqual "multi-root registration inventory" ["base","left","middle","right"] (sort ordered)
        assertBool "dependencies precede both reversed roots" $
          all (\(dependency,dependent) -> elemIndex dependency ordered < elemIndex dependent ordered)
            [("base","middle"),("middle","left"),("base","right")]
  , TestCase $ rejects (order [unit "base" [],unit "base" []])
  , TestCase $ rejects (order [unit "left" ["right"],unit "right" ["left"]])
  , TestCase $ rejects (order [unit "self" ["self"]])
  ]
  where
    nativeText = ["Data.Text", "Data.Text.Internal.Validate", "Data.Text.Internal.Validate.Native"]
    unit name dependencies = InstalledUnit name "" dependencies [] []
    order = fmap (map registeredId) . pinnedDependencyOrder
    rejects result = case result of
      Left message -> assertBool "missing evidence has a diagnostic" (not $ null message)
      Right flags -> assertFailure ("missing evidence guessed flags: " ++ show flags)
