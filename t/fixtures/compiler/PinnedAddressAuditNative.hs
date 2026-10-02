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
-- Native GHC observer for the pinned address audit fixture.
module Main where
import Data.Word (Word8)
import Foreign.Marshal.Alloc (allocaBytesAligned)
import Foreign.Storable (poke, peekByteOff)
import GHC.Exts (Int(I#))
import GHC.Fingerprint.Type (Fingerprint(..))
import qualified PinnedAddressAudit as P

dispatch :: [String] -> IO ()
dispatch tokens = do
  answer <- case tokens of
    ["fingerprintByte", x, y, i] -> allocaBytesAligned 16 8 (\pointer -> do
      poke pointer (Fingerprint (fromIntegral (read x :: Int)) (fromIntegral (read y :: Int)))
      byte <- peekByteOff pointer (read i) :: IO Word8
      pure (fromIntegral byte))
    _ -> pure $ case tokens of
      ["pinnedBytes", n, i, x] -> case (read n, read i, read x) of
        (I# a, I# b, I# c) -> I# (P.pinnedBytes a b c)
      ["alignedBytes", n, a, i, x] -> case (read n, read a, read i, read x) of
        (I# b, I# c, I# d, I# e) -> I# (P.alignedBytes b c d e)
      ["keepAliveWord8", x] -> case read x of I# a -> I# (P.keepAliveWord8 a)
      ["keepAliveLazy", x] -> case read x of I# a -> I# (P.keepAliveLazy a)
      _ -> error "invalid pinned-address request"
  putStrLn (concatMap (++ "\t") tokens ++ show answer)

main :: IO ()
main = getContents >>= mapM_ (dispatch . words) . lines
