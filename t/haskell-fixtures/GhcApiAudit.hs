-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : GhcApiAudit
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Native GHC; host filesystem
--
-- Explicit audit selection and truthful evidence for GHC API fixtures.
module GhcApiAudit (ghcApiOptions, ghcApiAuditArguments, ghcApiAuditEvidence) where

import Data.Aeson (Value(..), eitherDecodeStrict')
import qualified Data.Aeson.KeyMap as KeyMap
import qualified Data.ByteString as BS

-- | Keep the ordinary no-audit launch path unless the caller requests a scan.
ghcApiOptions :: [String] -> Either String (Bool, [String])
ghcApiOptions arguments
  | length (filter (== "--audit") arguments) > 1 = Left "ghc-api: repeated --audit"
  | any (`notElem` ["faststring", "session", "load"]) selected =
      Left "ghc-api: select [--audit] [faststring|session|load ...]"
  | otherwise = Right ("--audit" `elem` arguments,
      if null selected then ["faststring", "session", "load"] else selected)
  where selected = filter (/= "--audit") arguments

ghcApiAuditArguments :: Bool -> [String]
ghcApiAuditArguments requested = ["--audit" | requested]

-- | An unrequested report, even a leftover accepted report, is not evidence
-- for this invocation. Return no audit artifact and never read it in that case.
-- A requested audit must have a well-formed, accepting production report.
ghcApiAuditEvidence :: Bool -> FilePath -> IO (Maybe Bool, [FilePath])
ghcApiAuditEvidence False _ = pure (Nothing, [])
ghcApiAuditEvidence True path = do
  bytes <- BS.readFile path
  report <- either (fail . ("ghc-api: invalid audit JSON: " ++)) pure (eitherDecodeStrict' bytes)
  case report of
    Object fields | KeyMap.lookup "accepted" fields == Just (Bool True) ->
      pure (Just True, [path])
    _ -> fail "ghc-api: requested production strict audit did not accept"
