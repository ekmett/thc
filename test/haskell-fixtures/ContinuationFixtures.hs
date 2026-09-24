-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

module ContinuationFixtures (prepareCoreContinuation) where

import Control.Monad (unless)
import FixtureSupport (run)
import System.Directory (createDirectoryIfMissing)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>))

prepareCoreContinuation :: FilePath -> IO ()
prepareCoreContinuation root = do
  let base = root </> "build/core-continuation"
      source = "compiler/test-fixtures/CoreContinuationAudit.hs"
      lazySource = "compiler/test-fixtures/LazyIOCallbackAudit.hs"
  mapM_ (createDirectoryIfMissing True . (base </>)) ["core", "ghc", "native"]
  _ <- run root [("THC_CORE_OUT", base </> "core"), ("THC_GHC_OUT", base </> "ghc")]
       "compiler/export.sh" [source, lazySource] ""
  mapM_ (\(entry, report) -> run root [] "python3"
      ["scripts/audit-core.py", base </> "core/LazyIOCallbackAudit.json",
       "--entry", entry, "--output", base </> report] "")
      [("catchLazyActionHead", "lazy-action-audit.json"),
       ("catchLazyHandlerHead", "lazy-handler-audit.json"),
       ("keepAliveScalar", "keep-alive-scalar-audit.json"),
       ("keepAliveTuple", "keep-alive-tuple-audit.json")]
  _ <- run root [] "python3" ["scripts/audit-core.py", base </> "core/CoreContinuationAudit.json",
       "--entry", "sharedAnswer", "--output", base </> "audit.json"] ""
  _ <- run root [] "python3" ["scripts/audit-core.py", base </> "core/CoreContinuationAudit.json",
       "--entry", "applicationAnswer", "--output", base </> "application-audit.json"] ""
  _ <- run root [] "python3" ["scripts/audit-core.py", base </> "core/CoreContinuationAudit.json",
       "--entry", "overapplicationThunk", "--output", base </> "overapplication-audit.json"] ""
  _ <- run root [] "python3" ["scripts/audit-core.py", base </> "core/CoreContinuationAudit.json",
       "--entry", "overapplicationTail", "--output", base </> "overapplication-tail-audit.json"] ""
  _ <- run root [] "python3" ["scripts/audit-core.py", base </> "core/CoreContinuationAudit.json",
       "--entry", "directOverapplicationTailThunk", "--output", base </> "direct-overapplication-tail-audit.json"] ""
  mapM_ (\(entry, report) -> run root [] "python3"
      ["scripts/audit-core.py", base </> "core/CoreContinuationAudit.json",
       "--entry", entry, "--output", base </> report] "")
      [("compactScalarAnswer", "compact-scalar-audit.json"),
       ("typedScalarAnswer", "typed-scalar-audit.json")]
  _ <- run root [] "python3" ["scripts/audit-core.py", base </> "core/CoreContinuationAudit.json",
       "--entry", "nestedApplication", "--output", base </> "nested-audit.json"] ""
  _ <- run root [] "python3" ["scripts/audit-core.py", base </> "core/CoreContinuationAudit.json",
       "--entry", "catchActionAnswer", "--output", base </> "catch-action-audit.json"] ""
  _ <- run root [] "python3" ["scripts/audit-core.py", base </> "core/CoreContinuationAudit.json",
       "--entry", "catchActionFailure", "--output", base </> "catch-failure-audit.json"] ""
  _ <- run root [] "python3" ["scripts/audit-core.py", base </> "core/CoreContinuationAudit.json",
       "--entry", "nestedCatchAction", "--output", base </> "nested-catch-audit.json"] ""
  _ <- run root [] "python3" ["scripts/audit-core.py", base </> "core/CoreContinuationAudit.json",
       "--entry", "asyncPayload", "--output", base </> "async-payload-audit.json"] ""
  _ <- run root [] "python3" ["scripts/audit-core.py", base </> "core/CoreContinuationAudit.json",
       "--entry", "tupleApplicationAnswer", "--output", base </> "tuple-application-audit.json"] ""
  _ <- run root [] "python3" ["scripts/audit-core.py", base </> "core/CoreContinuationAudit.json",
       "--entry", "tupleOverapplicationThunk", "--output", base </> "tuple-overapplication-audit.json"] ""
  _ <- run root [] "python3" ["scripts/audit-core.py", base </> "core/CoreContinuationAudit.json",
       "--entry", "tupleApplicationFailure", "--output", base </> "tuple-application-failure-audit.json"] ""
  _ <- run root [] "python3" ["scripts/audit-core.py", base </> "core/CoreContinuationAudit.json",
       "--entry", "tupleCompactAnswer", "--output", base </> "tuple-compact-audit.json"] ""
  _ <- run root [] "python3" ["scripts/audit-core.py", base </> "core/CoreContinuationAudit.json",
       "--entry", "tupleRaiseAnswer", "--output", base </> "tuple-raise-audit.json"] ""
  _ <- run root [] "python3" ["scripts/audit-core.py", base </> "core/CoreContinuationAudit.json",
       "--entry", "catchHandlerAnswer", "--output", base </> "handler-audit.json"] ""
  mapM_ (\(entry, report) -> run root [] "python3"
      ["scripts/audit-core.py", base </> "core/CoreContinuationAudit.json",
       "--entry", entry, "--output", base </> report] "")
      [("maskedCheckpointAnswer", "masked-audit.json"),
       ("unmaskedCheckpointAnswer", "unmasked-audit.json"),
       ("uninterruptibleCheckpointAnswer", "uninterruptible-audit.json")]
  _ <- run root [] "python3" ["scripts/audit-core.py", base </> "core/CoreContinuationAudit.json",
       "--entry", "forceNonlocalAnswer", "--output", base </> "force-value-audit.json"] ""
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- run root [] ghc ["--numeric-version"] ""
  unless (takeWhile (/= '\n') version == "9.14.1") (die "core-continuation requires GHC 9.14.1")
  _ <- run root [] ghc ["-O2", "-i./compiler/test-fixtures", "-odir", base </> "native",
       "-hidir", base </> "native", "-o", base </> "native-oracle",
       "compiler/test-fixtures/CoreContinuationNative.hs", source] ""
  native <- run root [] (base </> "native-oracle") [] ""
  unless (native == "108\n208\n42\n77\n43\n114\n114\n79\n2\n0\n1\n208\n208\n209\n209\n208\n8\n114\n")
    (die "core-continuation native oracle disagreed with checkpoint results")
  writeFile (base </> "native-output.txt") native
  _ <- run root [] ghc ["-O2", "-i./compiler/test-fixtures", "-odir", base </> "native",
       "-hidir", base </> "native", "-o", base </> "lazy-native-oracle",
       "compiler/test-fixtures/LazyIOCallbackNative.hs", lazySource] ""
  lazyNative <- run root [] (base </> "lazy-native-oracle") [] ""
  unless (lazyNative == "42\n77\n43\n44\n") (die "lazy IO callbacks disagree with native GHC")
  writeFile (base </> "lazy-native-output.txt") lazyNative
