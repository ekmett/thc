-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- |
-- Module      : Main
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Haskell 2010; native flat-JSON conversion
--
-- Explicit compact conversion controls. Debug omission must be requested;
-- unsupported semantic provenance fails before publishing a container.
module Main (main) where

import Data.Aeson (eitherDecodeStrict', encode)
import qualified Data.ByteString as BS
import qualified Data.ByteString.Lazy as BL
import System.Environment (getArgs)
import System.Exit (die)
import Text.Read (readMaybe)
import THC.Compact.JSON (parseModuleWithoutDebug, parseModuleWithDebug)
import THC.Compact.Inspect (inspectContainer, inspectName, inspectSource)
import THC.Compact.Module (writeModule, writeModuleWithDebug)

main :: IO ()
main = do
  arguments <- getArgs
  case arguments of
    ["encode",source,destination] -> do
      value <- BS.readFile source >>= either die pure . eitherDecodeStrict'
      (facts,bindings,annotations) <- either die pure (parseModuleWithDebug value)
      _ <- writeModuleWithDebug destination facts bindings annotations
      pure ()
    ["encode","--without-debug",source,destination] -> do
      value <- BS.readFile source >>= either die pure . eitherDecodeStrict'
      (facts,bindings) <- either die pure (parseModuleWithoutDebug value)
      _ <- writeModule destination facts bindings
      pure ()
    ["decode",source,destination] -> do
      value <- BS.readFile source >>= either die pure . inspectContainer
      BL.writeFile destination (encode value)
    ["source",source,offset] -> do
      position <- unsigned offset
      value <- BS.readFile source >>= either die pure . flip inspectSource position
      BL.putStr (encode value)
      putStrLn ""
    ["name",source,scope,slot] -> do
      binding <- unsigned scope
      ordinal <- unsigned slot
      value <- BS.readFile source >>= either die pure . (\bytes -> inspectName bytes binding ordinal)
      BL.putStr (encode value)
      putStrLn ""
    ["--help"] -> putStrLn usage
    _ -> die usage
  where
    usage = "Usage: thc-compact encode [--without-debug] MODULE.json MODULE.thcc\n       thc-compact decode MODULE.thcc MODULE.json\n       thc-compact source MODULE.thcc DATA_OFFSET\n       thc-compact name MODULE.thcc BINDING_OFFSET ORDINAL_SLOT"
    unsigned text = case readMaybe text of
      Just value | value >= (0::Integer) && value <= 18446744073709551615 -> pure (fromInteger value)
      _ -> die "Expected unsigned 64-bit decimal position"
