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
  mapM_ (createDirectoryIfMissing True . (base </>)) ["core", "ghc", "native"]
  _ <- run root [("THC_CORE_OUT", base </> "core"), ("THC_GHC_OUT", base </> "ghc")]
       "compiler/export.sh" [source] ""
  _ <- run root [] "python3" ["scripts/audit-core.py", base </> "core/CoreContinuationAudit.json",
       "--entry", "sharedAnswer", "--output", base </> "audit.json"] ""
  _ <- run root [] "python3" ["scripts/audit-core.py", base </> "core/CoreContinuationAudit.json",
       "--entry", "applicationAnswer", "--output", base </> "application-audit.json"] ""
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
       "--entry", "tupleApplicationFailure", "--output", base </> "tuple-application-failure-audit.json"] ""
  _ <- run root [] "python3" ["scripts/audit-core.py", base </> "core/CoreContinuationAudit.json",
       "--entry", "tupleCompactAnswer", "--output", base </> "tuple-compact-audit.json"] ""
  _ <- run root [] "python3" ["scripts/audit-core.py", base </> "core/CoreContinuationAudit.json",
       "--entry", "tupleRaiseAnswer", "--output", base </> "tuple-raise-audit.json"] ""
  _ <- run root [] "python3" ["scripts/audit-core.py", base </> "core/CoreContinuationAudit.json",
       "--entry", "catchHandlerAnswer", "--output", base </> "handler-audit.json"] ""
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- run root [] ghc ["--numeric-version"] ""
  unless (takeWhile (/= '\n') version == "9.14.1") (die "core-continuation requires GHC 9.14.1")
  _ <- run root [] ghc ["-O2", "-i./compiler/test-fixtures", "-odir", base </> "native",
       "-hidir", base </> "native", "-o", base </> "native-oracle",
       "compiler/test-fixtures/CoreContinuationNative.hs", source] ""
  native <- run root [] (base </> "native-oracle") [] ""
  unless (native == "108\n208\n42\n77\n43\n114\n114\n79\n") (die "core-continuation native oracle expected 108, 208, 42, 77, 43, 114, 114 and 79")
  writeFile (base </> "native-output.txt") native
