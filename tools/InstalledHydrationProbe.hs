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
-- Probe installed Core acquisition while retaining exact input and output identities.
module Main (main) where

import Control.Monad (forM)
import qualified Crypto.Hash.SHA256 as SHA256
import Data.Aeson (object, (.=), encode)
import qualified Data.ByteString as BS
import qualified Data.ByteString.Lazy.Char8 as BL
import GHC.Clock (getMonotonicTimeNSec)
import Numeric (showHex)
import System.Environment (getArgs, lookupEnv)
import System.Exit (die)
import THC.Driver.Installed

-- One cold production acquisition, without ZIP/cache work or guest execution.
-- Compile this same probe against either revision's Installed module. Hashes
-- preserve inventory order and let comparisons reject changed inputs/results.
-- Wrap the executable with the host's resource/time tool for process CPU/RSS.
main :: IO ()
main = do
  arguments <- getArgs
  case arguments of
    [ghc, packageTool, helper, identifier] -> do
      jobs <- lookupEnv "THC_INSTALLED_CORE_JOBS"
      helperHash <- digest <$> BS.readFile helper
      length helperHash `seq` pure ()
      context <- installedContext ghc packageTool helper [] (object ["id" .= ("ghc-9.14.1" :: String)])
      unit <- discoverInstalled context identifier
      inputs <- forM (installedInterfaces unit) $ \(name, path) -> do
        bytes <- BS.readFile path
        pure (object ["module" .= name, "sha256" .= digest bytes])
      -- Force input hashes outside the hydration interval too.
      let inputHash = digest (BL.toStrict (encode inputs))
      length inputHash `seq` pure ()
      start <- getMonotonicTimeNSec
      result <- acquireInstalled context unit
      end <- getMonotonicTimeNSec
      case result of
        Left missing -> die ("Complete Core unavailable: " ++ show missing)
        Right core -> do
          let rows = [object ["module" .= name, "bytes" .= BS.length bytes,
                              "sha256" .= digest bytes] | (name, bytes) <- coreModules core]
          BL.putStrLn (encode (object
            ["unit" .= identifier, "owner" .= coreOwner core, "jobsEnvironment" .= jobs,
             "helperSha256" .= helperHash, "inputInventorySha256" .= inputHash,
             "provenanceSha256" .= digest (BL.toStrict (encode (installedProvenance context unit))),
             "hydrationElapsedNs" .= (end - start), "modules" .= rows,
             "payloadBytes" .= sum (map (BS.length . snd) (coreModules core))]))
    _ -> die "Usage: installed-hydration-probe GHC GHC_PKG THC_INTERFACE REGISTERED_UNIT"
  where
    digest = concatMap (\byte -> let value = showHex byte "" in replicate (2 - length value) '0' ++ value) . BS.unpack . SHA256.hash
