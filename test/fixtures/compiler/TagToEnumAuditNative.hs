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
-- Native GHC observer for the tag to enum audit fixture.
module Main where
import TagToEnumAudit
import GHC.Exts
import System.Environment (getArgs)
main :: IO ()
main = do
  [path] <- getArgs
  contents <- readFile path
  mapM_ run (map words (lines contents))
 where
  run [name, text] = case read text of
    I# n -> let value = case name of
                  "boolCase" -> boolCase n
                  "orderingCase" -> orderingCase n
                  "colourCase" -> colourCase n
                  "wrappedColourCase" -> wrappedColourCase n
                  "externalCase" -> externalCase n
                  "lazyCase" -> lazyCase n
                  "lazyTagCase" -> lazyTagCase n
                  "lazyWrappedTagCase" -> lazyWrappedTagCase n
                  "papCase" -> papCase n
                  "onceCase" -> onceCase n
                  _ -> error name
            in putStrLn (name ++ "\t" ++ text ++ "\t" ++ show (I# value))
  run row = error (show row)
