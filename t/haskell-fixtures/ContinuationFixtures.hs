-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- |
-- Module      : ContinuationFixtures
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Native GHC; host filesystem/process services
--
-- Fixture acquisition support for continuation.
module ContinuationFixtures (prepareCoreContinuation) where

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
      literalSource = "t/fixtures/compiler/LargeLiteralCaseAudit.hs"
  mapM_ (createDirectoryIfMissing True . (base </>)) ["core", "ghc", "native"]
  _ <- run root [("THC_CORE_OUT", base </> "core"), ("THC_GHC_OUT", base </> "ghc")]
       "bin/export-core.sh" [source, lazySource, literalSource] ""
  mapM_ (\entry -> run root [] "python3"
      ["bin/audit-core.py", base </> "core/LargeLiteralCaseAudit.cbd",
       "--entry", "main:LargeLiteralCaseAudit." ++ entry, "--output", base </> ("literal-" ++ entry ++ "-audit.json")] "")
      ["largeInt", "largeWordCheck", "largeLazy", "boxInt"]
  mapM_ (\(entry, report) -> run root [] "python3"
      ["bin/audit-core.py", base </> "core/LazyIOCallbackAudit.cbd",
       "--entry", "main:LazyIOCallbackAudit." ++ entry, "--output", base </> report] "")
      [("catchLazyActionHead", "lazy-action-audit.json"),
       ("catchLazyHandlerHead", "lazy-handler-audit.json"),
       ("keepAliveScalar", "keep-alive-scalar-audit.json"),
       ("keepAliveTuple", "keep-alive-tuple-audit.json")]
  _ <- run root [] "python3" ["bin/audit-core.py", base </> "core/CoreContinuationAudit.cbd",
       "--entry", "main:CoreContinuationAudit.sharedAnswer", "--output", base </> "audit.json"] ""
  _ <- run root [] "python3" ["bin/audit-core.py", base </> "core/CoreContinuationAudit.cbd",
       "--entry", "main:CoreContinuationAudit.applicationAnswer", "--output", base </> "application-audit.json"] ""
  _ <- run root [] "python3" ["bin/audit-core.py", base </> "core/CoreContinuationAudit.cbd",
       "--entry", "main:CoreContinuationAudit.overapplicationThunk", "--output", base </> "overapplication-audit.json"] ""
  _ <- run root [] "python3" ["bin/audit-core.py", base </> "core/CoreContinuationAudit.cbd",
       "--entry", "main:CoreContinuationAudit.overapplicationTail", "--output", base </> "overapplication-tail-audit.json"] ""
  _ <- run root [] "python3" ["bin/audit-core.py", base </> "core/CoreContinuationAudit.cbd",
       "--entry", "main:CoreContinuationAudit.directOverapplicationTailThunk", "--output", base </> "direct-overapplication-tail-audit.json"] ""
  mapM_ (\(entry, report) -> run root [] "python3"
      ["bin/audit-core.py", base </> "core/CoreContinuationAudit.cbd",
       "--entry", "main:CoreContinuationAudit." ++ entry, "--output", base </> report] "")
      [("compactScalarAnswer", "compact-scalar-audit.json"),
       ("typedScalarAnswer", "typed-scalar-audit.json")]
  _ <- run root [] "python3" ["bin/audit-core.py", base </> "core/CoreContinuationAudit.cbd",
       "--entry", "main:CoreContinuationAudit.nestedApplication", "--output", base </> "nested-audit.json"] ""
  _ <- run root [] "python3" ["bin/audit-core.py", base </> "core/CoreContinuationAudit.cbd",
       "--entry", "main:CoreContinuationAudit.catchActionAnswer", "--output", base </> "catch-action-audit.json"] ""
  _ <- run root [] "python3" ["bin/audit-core.py", base </> "core/CoreContinuationAudit.cbd",
       "--entry", "main:CoreContinuationAudit.catchActionFailure", "--output", base </> "catch-failure-audit.json"] ""
  _ <- run root [] "python3" ["bin/audit-core.py", base </> "core/CoreContinuationAudit.cbd",
       "--entry", "main:CoreContinuationAudit.nestedCatchAction", "--output", base </> "nested-catch-audit.json"] ""
  _ <- run root [] "python3" ["bin/audit-core.py", base </> "core/CoreContinuationAudit.cbd",
       "--entry", "main:CoreContinuationAudit.asyncPayload", "--output", base </> "async-payload-audit.json"] ""
  _ <- run root [] "python3" ["bin/audit-core.py", base </> "core/CoreContinuationAudit.cbd",
       "--entry", "main:CoreContinuationAudit.tupleApplicationAnswer", "--output", base </> "tuple-application-audit.json"] ""
  _ <- run root [] "python3" ["bin/audit-core.py", base </> "core/CoreContinuationAudit.cbd",
       "--entry", "main:CoreContinuationAudit.tupleOverapplicationThunk", "--output", base </> "tuple-overapplication-audit.json"] ""
  _ <- run root [] "python3" ["bin/audit-core.py", base </> "core/CoreContinuationAudit.cbd",
       "--entry", "main:CoreContinuationAudit.tupleTailOverapplicationThunk", "--output", base </> "tuple-tail-overapplication-audit.json"] ""
  _ <- run root [] "python3" ["bin/audit-core.py", base </> "core/CoreContinuationAudit.cbd",
       "--entry", "main:CoreContinuationAudit.tupleApplicationFailure", "--output", base </> "tuple-application-failure-audit.json"] ""
  _ <- run root [] "python3" ["bin/audit-core.py", base </> "core/CoreContinuationAudit.cbd",
       "--entry", "main:CoreContinuationAudit.tupleCompactAnswer", "--output", base </> "tuple-compact-audit.json"] ""
  _ <- run root [] "python3" ["bin/audit-core.py", base </> "core/CoreContinuationAudit.cbd",
       "--entry", "main:CoreContinuationAudit.tupleRaiseAnswer", "--output", base </> "tuple-raise-audit.json"] ""
  _ <- run root [] "python3" ["bin/audit-core.py", base </> "core/CoreContinuationAudit.cbd",
       "--entry", "main:CoreContinuationAudit.catchHandlerAnswer", "--output", base </> "handler-audit.json"] ""
  mapM_ (\(entry, report) -> run root [] "python3"
      ["bin/audit-core.py", base </> "core/CoreContinuationAudit.cbd",
       "--entry", "main:CoreContinuationAudit." ++ entry, "--output", base </> report] "")
      [("maskedCheckpointAnswer", "masked-audit.json"),
       ("unmaskedCheckpointAnswer", "unmasked-audit.json"),
       ("uninterruptibleCheckpointAnswer", "uninterruptible-audit.json")]
  _ <- run root [] "python3" ["bin/audit-core.py", base </> "core/CoreContinuationAudit.cbd",
       "--entry", "main:CoreContinuationAudit.forceNonlocalAnswer", "--output", base </> "force-value-audit.json"] ""
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- run root [] ghc ["--numeric-version"] ""
  unless (takeWhile (/= '\n') version == "9.14.1") (die "core-continuation requires GHC 9.14.1")
  _ <- run root [] ghc ["-O2", "-i./t/fixtures/compiler", "-odir", base </> "native",
       "-hidir", base </> "native", "-o", base </> "native-oracle",
       "t/fixtures/compiler/CoreContinuationNative.hs", source] ""
  native <- run root [] (base </> "native-oracle") [] ""
  unless (native == "108\n208\n42\n77\n43\n114\n114\n79\n2\n0\n1\n208\n208\n209\n209\n208\n8\n114\n114\n")
    (die "core-continuation native oracle disagreed with checkpoint results")
  writeFile (base </> "native-output.txt") native
  _ <- run root [] ghc ["-O2", "-i./t/fixtures/compiler", "-odir", base </> "native",
       "-hidir", base </> "native", "-o", base </> "lazy-native-oracle",
       "t/fixtures/compiler/LazyIOCallbackNative.hs", lazySource] ""
  lazyNative <- run root [] (base </> "lazy-native-oracle") [] ""
  unless (lazyNative == "42\n77\n43\n44\n") (die "lazy IO callbacks disagree with native GHC")
  writeFile (base </> "lazy-native-output.txt") lazyNative
  _ <- run root [] ghc ["-O2", "-odir", base </> "native", "-hidir", base </> "native",
       "-main-is", "LargeLiteralCaseAudit.main", "-o", base </> "literal-native-oracle", literalSource] ""
  literalNative <- run root [] (base </> "literal-native-oracle") [] ""
  unless (length (lines literalNative) == 52) (die "large literal case oracle row count differs")
  writeFile (base </> "literal-native-output.txt") literalNative
  literalHashes <- hashes root [literalSource, "t/haskell-fixtures/ContinuationFixtures.hs",
    "build/core-continuation/core/LargeLiteralCaseAudit.cbd",
    "build/core-continuation/literal-native-output.txt"]
  writeJson (base </> "literal-manifest.json") (toJSON literalHashes)
