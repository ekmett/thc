-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}

-- |
-- Module      : Main
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC-specific primitive types and operations
--
-- Native GHC observer for the proxy void audit fixture.
module Main where
import Control.Exception (SomeException, evaluate, try)
import Control.Monad (unless)
import GHC.Exts
import ProxyVoidAudit

main :: IO ()
main = do
  mapM_ run [(name, f, a) | (name, f) <- entries, a <- inputs]
  mapM_ (\a -> run ("effect", effect, a)) [0, 1, 4097, maxBound]
  failed <- try (evaluate (I# (effect (-1#)))) :: IO (Either SomeException Int)
  unless (either (const True) (const False) failed) (error "Proxy# producer failed to throw")
  where
    entries = [("direct", direct), ("returned", returned), ("tupleCase", tupleCase)]
    inputs = [minBound, -4097, -1, 0, 1, 4097, maxBound] :: [Int]
    run (name, f, a@(I# x)) = putStrLn (name ++ "\t" ++ show a ++ "\t" ++ show (I# (f x)))
