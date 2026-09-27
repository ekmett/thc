-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : Main
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Native GHC; temporary host files
--
-- Bounded controls for optional audit selection and fixture evidence.
module Main (main) where

import Control.Exception (IOException, bracket, try)
import Control.Monad (forM_, unless)
import Data.Aeson (encode, object, (.=))
import qualified Data.ByteString as BS
import qualified Data.ByteString.Lazy.Char8 as BL
import GhcApiAudit
import System.Directory (getTemporaryDirectory, removeFile)
import System.FilePath ((</>))
import System.IO (hClose, openBinaryTempFile)

check :: String -> Bool -> IO ()
check label ok = unless ok (fail label)

mustFail :: String -> IO a -> IO ()
mustFail label action = do
  result <- try (action >> pure ()) :: IO (Either IOException ())
  case result of
    Left _ -> pure ()
    Right () -> fail (label ++ " unexpectedly accepted")

main :: IO ()
main = do
  check "default probes, no audit" (ghcApiOptions [] == Right (False, ["faststring", "session", "load"]))
  check "selected probe, no audit" (ghcApiOptions ["load"] == Right (False, ["load"]))
  check "explicit audit" (ghcApiOptions ["session", "--audit", "load"] == Right (True, ["session", "load"]))
  check "audit default probes" (ghcApiOptions ["--audit"] == Right (True, ["faststring", "session", "load"]))
  forM_ [["--audit", "--audit"], ["unknown"], ["--no-audit"]] $ \args ->
    check "unknown/repeated option" (case ghcApiOptions args of Left _ -> True; Right _ -> False)
  check "driver default remains no audit" (null (ghcApiAuditArguments False))
  check "explicit driver flag" (ghcApiAuditArguments True == ["--audit"])
  temporary <- getTemporaryDirectory
  bracket (openBinaryTempFile temporary "thc-ghc-api-audit.json")
    (\(path, _) -> removeFile path) $ \(path, handle) -> do
      hClose handle
      let absent = path </> "missing-audit.json"
      check "no report required" . (== (Nothing, [])) =<< ghcApiAuditEvidence False absent
      mustFail "requested missing report" (ghcApiAuditEvidence True absent)
      forM_ ["{malformed", "{\"accepted\":true}", "{\"accepted\":false}"] $ \stale -> do
        BS.writeFile path stale
        check "unrequested report ignored" . (== (Nothing, [])) =<< ghcApiAuditEvidence False path
        check "stale report preserved" . (== stale) =<< BS.readFile path
      forM_ ["{malformed", "{}", "[]", "{\"accepted\":false}", "{\"accepted\":null}", "{\"accepted\":\"true\"}"] $ \invalid -> do
        BS.writeFile path invalid
        mustFail "requested invalid/rejected report" (ghcApiAuditEvidence True path)
      BS.writeFile path "{\"accepted\":true}"
      check "accepting report and artifact retained" . (== (Just True, [path])) =<< ghcApiAuditEvidence True path
      (accepted, _) <- ghcApiAuditEvidence False path
      check "unaudited JSON is null, not false acceptance"
        (encode (object ["auditRequested" .= False, "strictAccepted" .= accepted]) ==
          "{\"auditRequested\":false,\"strictAccepted\":null}")
  BL.putStrLn "GHC API optional audit controls passed"
