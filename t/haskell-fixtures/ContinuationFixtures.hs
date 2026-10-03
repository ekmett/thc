-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- Fixture rationale (108 core-continuation / large-literal-cases)
-- Purpose: Check saved continuation/lazy callback behavior and, independently,
--   signed/unsigned large-literal selection and lazy tuple cases.
-- Produces/consumed result: Named CBDs, one audit per module, native observations.
-- Cost and overlap: Literal consumers build only their own tiny program. The two
--   continuation programs have separate object directories, with two batched audits.
-- Build status: CMake declares single-owner outputs for the two independent groups.
-- Detailed file inputs/outputs: docs/fixture-inputs.log, entry 108.

-- |
-- Module      : ContinuationFixtures
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Native GHC; host filesystem/process services
--
-- Fixture acquisition support for continuation.
module ContinuationFixtures (prepareCoreContinuation, prepareLargeLiteralCases) where

import Control.Monad (unless)
import Data.Aeson (toJSON)
import FixtureSupport (hashes, run, writeJson)
import System.Directory (createDirectoryIfMissing)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>))

prepareCoreContinuation :: FilePath -> IO ()
prepareCoreContinuation root = do
  let base = root </> "build/core-continuation"
      source = "t/fixtures/compiler/CoreContinuationAudit.hs"
      lazySource = "t/fixtures/compiler/LazyIOCallbackAudit.hs"
  mapM_ (createDirectoryIfMissing True . (base </>)) ["core", "ghc", "native", "native/lazy"]
  _ <- run root [("THC_CORE_OUT", base </> "core"), ("THC_GHC_OUT", base </> "ghc")]
       "bin/export-core.sh" [source, lazySource] ""
  _ <- run root [] "python3"
    (["bin/audit-core.py", base </> "core/CoreContinuationAudit.cbd", "--output", base </> "audit.json"] ++
     concatMap (\entry -> ["--entry", "main:CoreContinuationAudit." ++ entry])
       ["sharedAnswer", "applicationAnswer", "overapplicationThunk", "overapplicationTail", "directOverapplicationTailThunk", "compactScalarAnswer", "typedScalarAnswer", "nestedApplication", "catchActionAnswer", "catchActionFailure", "nestedCatchAction", "asyncPayload", "tupleApplicationAnswer", "tupleOverapplicationThunk", "tupleTailOverapplicationThunk", "tupleApplicationFailure", "tupleCompactAnswer", "tupleRaiseAnswer", "catchHandlerAnswer", "maskedCheckpointAnswer", "unmaskedCheckpointAnswer", "uninterruptibleCheckpointAnswer", "forceNonlocalAnswer"]) ""
  _ <- run root [] "python3"
    (["bin/audit-core.py", base </> "core/LazyIOCallbackAudit.cbd", "--output", base </> "lazy-audit.json"] ++
     concatMap (\entry -> ["--entry", "main:LazyIOCallbackAudit." ++ entry])
       ["catchLazyActionHead", "catchLazyHandlerHead", "keepAliveScalar", "keepAliveTuple"]) ""
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- run root [] ghc ["--numeric-version"] ""
  unless (takeWhile (/= '\n') version == "9.14.1") (die "core-continuation requires GHC 9.14.1")
  _ <- run root [] ghc ["--make", "-O2", "-fforce-recomp", "-i./t/fixtures/compiler", "-odir", base </> "native",
       "-hidir", base </> "native", "-o", base </> "native-oracle",
       "t/fixtures/compiler/CoreContinuationNative.hs", source] ""
  native <- run root [] (base </> "native-oracle") [] ""
  unless (native == "108\n208\n42\n77\n43\n114\n114\n79\n2\n0\n1\n208\n208\n209\n209\n208\n8\n114\n114\n")
    (die "core-continuation native oracle disagreed with checkpoint results")
  writeFile (base </> "native-output.txt") native
  _ <- run root [] ghc ["--make", "-O2", "-fforce-recomp", "-i./t/fixtures/compiler", "-odir", base </> "native/lazy",
       "-hidir", base </> "native/lazy", "-o", base </> "lazy-native-oracle",
       "t/fixtures/compiler/LazyIOCallbackNative.hs", lazySource] ""
  lazyNative <- run root [] (base </> "lazy-native-oracle") [] ""
  unless (lazyNative == "42\n77\n43\n44\n") (die "lazy IO callbacks disagree with native GHC")
  writeFile (base </> "lazy-native-output.txt") lazyNative

prepareLargeLiteralCases :: FilePath -> IO ()
prepareLargeLiteralCases root = do
  let directory = "build/large-literal-cases"
      base = root </> directory
      source = "t/fixtures/compiler/LargeLiteralCaseAudit.hs"
  mapM_ (createDirectoryIfMissing True . (base </>)) ["core", "ghc", "native"]
  _ <- run root [("THC_CORE_OUT", base </> "core"), ("THC_GHC_OUT", base </> "ghc")]
       "bin/export-core.sh" [source] ""
  _ <- run root [] "python3"
    (["bin/audit-core.py", base </> "core/LargeLiteralCaseAudit.cbd", "--output", base </> "audit.json"] ++
     concatMap (\entry -> ["--entry", "main:LargeLiteralCaseAudit." ++ entry])
       ["largeInt", "largeWordCheck", "largeLazy", "boxInt"]) ""
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  _ <- run root [] ghc ["--make", "-O2", "-fforce-recomp", "-odir", base </> "native", "-hidir", base </> "native",
       "-main-is", "LargeLiteralCaseAudit.main", "-o", base </> "literal-native-oracle", source] ""
  observed <- run root [] (base </> "literal-native-oracle") [] ""
  unless (length (lines observed) == 52) (die "large literal case oracle row count differs")
  writeFile (base </> "literal-native-output.txt") observed
  literalHashes <- hashes root [source, "t/haskell-fixtures/ContinuationFixtures.hs",
    directory </> "core/LargeLiteralCaseAudit.cbd", directory </> "literal-native-output.txt"]
  writeJson (base </> "literal-manifest.json") (toJSON literalHashes)
