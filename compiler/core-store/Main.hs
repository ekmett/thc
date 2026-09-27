-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- |
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : portable Haskell; whole-module inspection uses host memory
--
-- Explicit binary Core inspection and structural/scope verification. This tool
-- neither links foreign products nor establishes semantic runtime admission.
module Main (main) where

import Control.Monad (unless)
import qualified Data.ByteString as BS
import qualified Data.ByteString.Builder as BB
import Data.Char (digitToInt, isHexDigit)
import System.Environment (getArgs)
import System.Exit (die)
import System.IO (IOMode(WriteMode), stdout, withBinaryFile)
import Text.Read (readMaybe)
import THC.CoreStore.Decode (decodeStore)
import THC.CoreStore.Inspect (inspectBuilder)
import THC.CoreStore.Validate

usage :: String
usage = "thc-core-store inspect INPUT OUTPUT [--budget N|--unlimited] [--index-sha256 HEX]\n" ++
  "thc-core-store verify INPUT [--index-sha256 HEX]\n" ++
  "OUTPUT '-' writes JSON to stdout. verify checks structure/scopes, not runtime capability."

main :: IO ()
main = getArgs >>= \arguments -> case arguments of
  ["--help"] -> putStrLn usage
  "inspect":input:output:options -> do
    (digest,budget) <- parseOptions options
    store <- either die pure . decodeStore digest =<< BS.readFile input
    _ <- either die pure (validateStore store)
    result <- either die pure (inspectBuilder budget store)
    if output == "-" then BB.hPutBuilder stdout result else
      withBinaryFile output WriteMode (\handle -> BB.hPutBuilder handle result)
  "verify":input:options -> do
    (digest,_) <- parseOptions options
    store <- either die pure . decodeStore digest =<< BS.readFile input
    checked <- either die pure (validateStore store)
    putStrLn ("{\"schema\":1,\"structuralOnly\":true,\"records\":" ++ show (validatedNodeCount checked) ++
      ",\"binders\":" ++ show (validatedBinderCount checked) ++ ",\"scopes\":" ++ show (validatedScopeCount checked) ++ "}")
  _ -> die usage

parseOptions :: [String] -> IO (Maybe BS.ByteString,Maybe Integer)
parseOptions = go Nothing (Just 10000000) False
  where
    go digest budget _ [] = pure (digest,budget)
    go Nothing budget specified ("--index-sha256":hex:rest) = do
      unless (length hex == 64 && all isHexDigit hex) (die "Expected 64 hexadecimal SHA256 digits")
      go (Just (BS.pack (pairs hex))) budget specified rest
    go digest _ False ("--unlimited":rest) = go digest Nothing True rest
    go digest _ False ("--budget":amount:rest) = case readMaybe amount of
      Just limit | limit >= 0 -> go digest (Just limit) True rest
      _ -> die "Inspection budget must be a nonnegative integer"
    go _ _ _ _ = die usage
    pairs [] = []
    pairs (a:b:rest) = fromIntegral (digitToInt a * 16 + digitToInt b) : pairs rest
    pairs _ = error "SHA256 length already checked"
