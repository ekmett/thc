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
import THC.Compact.JSON (parseModuleWithoutDebug)
import THC.Compact.Inspect (inspectContainer)
import THC.Compact.Module (writeModule)

main :: IO ()
main = do
  arguments <- getArgs
  case arguments of
    ["encode","--without-debug",source,destination] -> do
      value <- BS.readFile source >>= either die pure . eitherDecodeStrict'
      (facts,bindings) <- either die pure (parseModuleWithoutDebug value)
      _ <- writeModule destination facts bindings
      pure ()
    ["decode",source,destination] -> do
      value <- BS.readFile source >>= either die pure . inspectContainer
      BL.writeFile destination (encode value)
    ["--help"] -> putStrLn usage
    _ -> die usage
  where usage = "Usage: thc-compact encode --without-debug MODULE.json MODULE.thcc\n       thc-compact decode MODULE.thcc MODULE.json"
