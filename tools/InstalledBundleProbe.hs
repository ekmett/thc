-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : Main
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC with the declared language extensions
--
-- Probe installed Core bundle publication and content-addressed cache integrity.
module Main (main) where

import Control.Exception (evaluate)
import Control.Monad (forM, unless)
import qualified Crypto.Hash.SHA256 as SHA
import Data.Aeson (encode, object, (.=))
import qualified Data.ByteString as BS
import qualified Data.ByteString.Lazy as BL
import Data.List (isInfixOf)
import GHC.Clock (getMonotonicTimeNSec)
import Numeric (showHex)
import System.CPUTime (getCPUTime)
import System.Environment (getArgs, getExecutablePath)
import System.Exit (die)
import System.FilePath ((</>))
import qualified System.Info as Info
import THC.Driver.Installed
import THC.Driver.Project

-- Prime a private real bundle cache, then time two complete warm production
-- validations. Pass an execve-only strace log to check helper counts without
-- replacing the real helper (whose bytes are part of the validation cost), or
-- "-" for uninstrumented timing. Every call must preserve the exact bundle;
-- traced controls also require zero hydration and at least one fresh probe.
main :: IO ()
main = do
  arguments <- getArgs
  case arguments of
    [ghc, packageTool, helper, database, identifier, recipe, cache, trace] -> do
      executable <- getExecutablePath
      driverHash <- hex . SHA.hash <$> BS.readFile executable
      let platform = Info.arch ++ "-" ++ if Info.os == "darwin" then "osx" else Info.os
          compiler = object ["id" .= ("ghc-9.14.1" :: String), "abi" .= ("bundle-probe" :: String),
            "platform" .= platform, "way" .= ("dynamic-nonprofiling" :: String)]
      context <- installedContext ghc packageTool helper [database] compiler
      unit <- discoverInstalled context identifier
      let acquire = prepareInstalledBundle cache (cache </> "staging") recipe driverHash context unit
            >>= either (fail . show) pure
          counts = do
            if trace == "-" then pure Nothing else do
              calls <- filter (isInfixOf ("execve(" ++ show helper ++ ",")) . lines <$> readFile trace
              probes <- evaluate (length (filter (isInfixOf "--probe-inventory") calls))
              loads <- evaluate (length (filter (isInfixOf "--module") calls))
              pure (Just (probes, loads))
      initial <- acquire
      let first = installedBundle initial
      measurements <- forM [1 :: Int, 2] $ \index -> do
        before <- counts
        start <- getMonotonicTimeNSec
        cpu <- getCPUTime
        result <- acquire
        endCpu <- getCPUTime
        end <- getMonotonicTimeNSec
        after <- counts
        let bundle = installedBundle result
            changes = case (before, after) of
              (Just (a, b), Just (c, d)) -> Just (c - a, d - b)
              _ -> Nothing
        unless (maybe True (\(probes, loads) -> probes > 0 && loads == 0) changes)
          (fail "Warm bundle validation skipped its fresh probe or hydrated Core")
        unless (bundleHash bundle == bundleHash first && bundleModules bundle == bundleModules first &&
                installedOwner result == installedOwner initial) (fail "Warm bundle contents changed")
        pure (object ["validation" .= index, "elapsedNs" .= (end - start),
          "parentCpuPs" .= (endCpu - cpu), "helperProbes" .= fmap fst changes,
          "helperLoads" .= fmap snd changes])
      BL.putStr (encode (object ["unit" .= identifier, "owner" .= installedOwner initial,
        "moduleInventorySha256" .= hex (SHA.hashlazy (encode (bundleModules first))),
        "modules" .= bundleModules first, "driverSha256" .= driverHash,
        "measurements" .= measurements]))
    _ -> die "Usage: installed-bundle-probe GHC GHC_PKG HELPER DATABASE UNIT RECIPE PRIVATE_CACHE EXECVE_TRACE_OR_DASH"
  where
    hex = concatMap (\byte -> let value = showHex byte "" in replicate (2 - length value) '0' ++ value) . BS.unpack
