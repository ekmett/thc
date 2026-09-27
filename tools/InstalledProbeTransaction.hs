-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE CPP #-}
{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : Main
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Native GHC; driver acquisition and host process services
--
-- Exercise installed-interface probe transactions and their cache boundaries.
module Main (main) where

import Control.Exception (evaluate)
import Control.Monad (forM, unless)
import qualified Crypto.Hash.SHA256 as SHA
import Data.Aeson (FromJSON, Result(..), Value(..), encode, fromJSON, object, (.=))
import qualified Data.Aeson.Key as Key
import qualified Data.Aeson.KeyMap as KM
import qualified Data.ByteString as BS
import qualified Data.ByteString.Lazy as BL
import GHC.Clock (getMonotonicTimeNSec)
import Numeric (showHex)
import System.CPUTime (getCPUTime)
import System.Environment (getArgs)
import System.Exit (die)
import System.IO (IOMode(ReadMode), withBinaryFile)
import THC.Driver.Installed

-- Compile this same source against the baseline driver, and against the
-- candidate with -DEXACT_PROBE. Only the production probe-action selection
-- differs. A preliminary untimed probe discovers the exact raw input set;
-- streamed hashes bracket the measured two-probe transaction. No bundle cache,
-- Core hydration, ZIP work, audit or guest execution is included.
main :: IO ()
main = do
  arguments <- getArgs
  case arguments of
    [ghc, packageTool, helper, identifier] -> do
      context <- installedContext ghc packageTool helper [] (object ["id" .= ("ghc-9.14.1" :: String)])
      unit <- discoverInstalled context identifier
      initial <- probeInstalled context unit
      rows <- field initial "interfaces" :: IO [Value]
      paths <- mapM (`field` "interface") rows
      let snapshot = do
            hashes <- forM (helper : paths) $ \path -> withBinaryFile path ReadMode $ \handle -> do
              bytes <- BL.hGetContents handle
              digest <- evaluate (SHA.hashlazy bytes)
              pure (path, hex digest)
            evaluate (hex (SHA.hashlazy (encode hashes)))
      before <- snapshot
#ifdef EXACT_PROBE
      action <- prepareInstalledProbe context unit
      let mode = "transaction-memo" :: String
#else
      let action = probeInstalled context unit
          mode = "fresh-probes" :: String
#endif
      measurements <- forM [1 :: Int, 2] $ \index -> do
        start <- getMonotonicTimeNSec
        cpu <- getCPUTime
        result <- action
        _ <- evaluate (BL.length (encode result))
        endCpu <- getCPUTime
        end <- getMonotonicTimeNSec
        unless (result == initial) (fail "Probe result changed during measured transaction")
        pure (object ["probe" .= index, "elapsedNs" .= (end - start), "parentCpuPs" .= (endCpu - cpu)])
      after <- snapshot
      unless (before == after) (fail "Raw inputs changed during measured transaction")
      BL.putStr (encode (object ["unit" .= identifier, "mode" .= mode,
        "interfaces" .= length paths, "rawInventorySha256" .= before,
        "probeSha256" .= hex (SHA.hashlazy (encode initial)), "measurements" .= measurements]))
    _ -> die "Usage: installed-probe-transaction GHC GHC_PKG THC_INTERFACE UNIT"
  where
    field :: FromJSON a => Value -> String -> IO a
    field (Object fields) name = case KM.lookup (Key.fromString name) fields of
      Just value -> case fromJSON value of Success result -> pure result; Error message -> fail message
      Nothing -> fail ("Missing field: " ++ name)
    field _ _ = fail "Expected object"
    hex = concatMap (\byte -> let value = showHex byte "" in replicate (2 - length value) '0' ++ value) . BS.unpack
