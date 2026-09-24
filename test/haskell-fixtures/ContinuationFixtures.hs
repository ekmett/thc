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
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- run root [] ghc ["--numeric-version"] ""
  unless (takeWhile (/= '\n') version == "9.14.1") (die "core-continuation requires GHC 9.14.1")
  _ <- run root [] ghc ["-O2", "-i./compiler/test-fixtures", "-odir", base </> "native",
       "-hidir", base </> "native", "-o", base </> "native-oracle",
       "compiler/test-fixtures/CoreContinuationNative.hs", source] ""
  native <- run root [] (base </> "native-oracle") [] ""
  unless (native == "108\n208\n") (die "core-continuation native oracle expected 108 and 208")
  writeFile (base </> "native-output.txt") native
