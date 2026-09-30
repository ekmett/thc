-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- |
-- Module      : Main
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Haskell 2010; native CBD inspection
--
-- Explicit CBD inspection. Executable inputs are binary; JSON is output only.
module Main (main) where

import Data.Aeson (encode)
import qualified Data.ByteString as BS
import qualified Data.ByteString.Lazy as BL
import System.Environment (getArgs)
import System.Exit (die)
import Text.Read (readMaybe)
import THC.Compact.Inspect (inspectContainer, inspectName, inspectSource, inspectSources)

main :: IO ()
main = do
  arguments <- getArgs
  case arguments of
    ["decode",source,destination] -> do
      value <- BS.readFile source >>= either die pure . inspectContainer
      BL.writeFile destination (encode value)
    ["sources",source] -> do
      value <- BS.readFile source >>= either die pure . inspectSources
      BL.putStr (encode value)
      putStrLn ""
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
    usage = "Usage: thc-compact decode MODULE.cbd MODULE.json\n       thc-compact sources MODULE.cbd\n       thc-compact source MODULE.cbd DATA_OFFSET\n       thc-compact name MODULE.cbd BINDING_OFFSET ORDINAL_SLOT"
    unsigned text = case readMaybe text of
      Just value | value >= (0::Integer) && value <= 18446744073709551615 -> pure (fromInteger value)
      _ -> die "Expected unsigned 64-bit decimal position"
