-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE ScopedTypeVariables #-}

-- |
-- Module      : Main
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC with the declared language extensions
--
-- Native GHC observer for the foreign exception fixture.
module Main where
import Control.Exception
import THC.Exception

main :: IO ()
main = do
  answer <- (pure (42 :: Int))
  ordinary <- (throwIO Overflow) `catch` \(e :: ArithException) ->
    pure (if e == Overflow then 77 else -1)
  lazy <- (throwIO (error "lazy SomeException" :: SomeException))
    `catch` \(_ :: SomeException) -> pure (99 :: Int)
  let projection = case fromException (toException Overflow) :: Maybe ForeignException of
        Nothing -> 1 :: Int
        Just _ -> 0
  print (answer, ordinary, lazy, projection)
