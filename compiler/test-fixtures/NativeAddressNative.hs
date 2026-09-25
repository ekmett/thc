-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}
module Main (main) where
import Control.Monad (unless)
import GHC.Exts (Int(I#), addr2Int#, int2Addr#)
import GHC.Ptr (Ptr(Ptr))
import Foreign.Marshal.Alloc (allocaBytes)
import Foreign.Ptr (nullPtr, plusPtr)
import Foreign.Storable (peekByteOff, pokeByteOff)
import Data.Word (Word8)
import System.Mem (performGC)

{-# NOINLINE addressBits #-}
addressBits :: Ptr a -> Int
addressBits (Ptr a#) = I# (addr2Int# a#)
{-# NOINLINE addressFromBits #-}
addressFromBits :: Int -> Ptr a
addressFromBits (I# n#) = Ptr (int2Addr# n#)

main :: IO ()
main = allocaBytes 32 $ \base -> do
  let cases = [minBound, -4096, -1, 0, 1, 4096, maxBound] :: [Int]
      integralRoundTrips = all (\n -> addressBits (addressFromBits n) == n) cases
      offset = base `plusPtr` 7
      bits = addressBits offset
      restored = addressFromBits bits
  pokeByteOff base 7 (37 :: Word8)
  before <- peekByteOff restored 0 :: IO Word8
  pokeByteOff restored 0 (91 :: Word8)
  after <- peekByteOff base 7 :: IO Word8
  performGC
  alive <- peekByteOff restored 0 :: IO Word8
  let checks = [addressBits nullPtr == 0, addressFromBits 0 == (nullPtr :: Ptr ()),
        integralRoundTrips, restored == offset, bits - addressBits base == 7,
        addressFromBits (addressBits base + 32) == base `plusPtr` 32,
        before == 37, after == 91, alive == 91]
  unless (and checks) (fail (show checks))
  print checks
