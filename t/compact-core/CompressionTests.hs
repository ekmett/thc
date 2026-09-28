-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- |
-- Module      : CompressionTests
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Haskell 2010; native HUnit policy controls
--
-- CBD compression precedence and input validation, without output side effects.
module CompressionTests (compressionTests) where

import Control.Monad (foldM, forM_)
import Data.Either (isLeft)
import Test.HUnit
import THC.Compact.Compression

compressionTests :: Test
compressionTests = TestList
  [ TestLabel "CBD all members default to STORED" $ TestCase $
      forM_ members $ \member -> assertEqual (memberName member) 0 (compressionLevel member defaultCompression)
  , TestLabel "CBD exact supported member names" $ TestCase $
      assertEqual "CLI and ZIP vocabulary"
        ["header","data","strings","symbols","names","filenames","line-columns"] (map memberName members)
  , TestLabel "CBD all global levels" $ TestCase $
      forM_ [0..9] $ \level -> do
        policy <- parsed [show level]
        forM_ members $ \member -> assertEqual (show level) level (compressionLevel member policy)
  , TestLabel "CBD overrides beat globals in either order" $ TestCase $
      forM_ members $ \member -> do
        first <- parsed [memberName member ++ "=1","9"]
        last' <- parsed ["9",memberName member ++ "=1"]
        assertEqual "order-independent scope priority" first last'
        forM_ members $ \other -> assertEqual (memberName other)
          (if other == member then 1 else 9) (compressionLevel other first)
  , TestLabel "CBD last value in each scope wins" $ TestCase $ do
      policy <- parsed ["data=9","1","names=8","data=0","7","names=2"]
      assertEqual "explicit STORED survives later global" 0 (compressionLevel DataMember policy)
      assertEqual "last type value" 2 (compressionLevel NamesMember policy)
      assertEqual "last global value" 7 (compressionLevel StringsMember policy)
  , TestLabel "CBD malformed policies reject before IO" $ TestCase $
      forM_ ["","-1","10","01","+1"," 1","1 ","1.0","all=1","unknown=0",
        "DATA=1","=1","data=","data=-1","data=10","data=01","data=1=2","data =1"] $ \argument ->
        assertBool argument (isLeft (setCompression argument defaultCompression))
  , TestLabel "CBD encoder CLI parses both option spellings before paths" $ TestCase $ do
      (policy,omit,source,destination) <- either assertFailure pure (parseEncodingOptions
        ["--cbd-compression=data=1","--without-debug","--cbd-compression","9","in.json","out.cbd"])
      assertEqual "explicit override" 1 (compressionLevel DataMember policy)
      assertEqual "global level" 9 (compressionLevel StringsMember policy)
      assertEqual "debug option" True omit
      assertEqual "unchanged paths" ("in.json","out.cbd") (source,destination)
      forM_ [["--cbd-compression"],["--cbd-compression=10","in","out"],
        ["--cbd-compression","data=","in","out"],["--unknown","in","out"],
        ["in","out","extra"]] $ \arguments ->
          assertBool (show arguments) (isLeft (parseEncodingOptions arguments))
  ]
  where
    members = [minBound..maxBound]
    parsed = either assertFailure pure . foldM (flip setCompression) defaultCompression
