-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}
module Main where

import GHC.Exts
import Data.Bits (finiteBitSize)
import System.Environment (getArgs)
import qualified SynchronousExceptionsAudit as P

emit :: String -> (Int# -> Int#) -> Int -> IO ()
emit name action value@(I# input) =
  putStrLn (name ++ "\t" ++ show value ++ "\t" ++ show (I# (action input)))

dispatch :: [String] -> IO ()
dispatch [name, value] = case name of
  "preciseCatch" -> emit name P.preciseCatch input
  "actionHeadCatch" -> emit name P.actionHeadCatch input
  "ignoredBottomPayload" -> emit name P.ignoredBottomPayload input
  "nestedRethrow" -> emit name P.nestedRethrow input
  "unusedHandler" -> emit name P.unusedHandler input
  "lazyResultBoundary" -> emit name P.lazyResultBoundary input
  "restoreAndRethrow" -> emit name P.restoreAndRethrow input
  "handlerMaskState" -> emit name P.handlerMaskState input
  "maskNested" -> emit name P.maskNested input
  "maskRethrowRestore" -> emit name P.maskRethrowRestore input
  "noDuplicateProbe" -> emit name P.noDuplicateProbe input
  _ -> error "unknown synchronous exception oracle entry"
  where input = read value
dispatch _ = error "malformed synchronous exception oracle request"

main :: IO ()
main = do
  arguments <- getArgs
  case arguments of
    ["--word-bits"] -> print (finiteBitSize (0 :: Int))
    [] -> getContents >>= mapM_ (dispatch . words) . lines
    _ -> error "unknown synchronous exception oracle argument"
