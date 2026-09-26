-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE CApiFFI, ForeignFunctionInterface, MagicHash, UnboxedTuples #-}
module StableForeign (stableRoundtrip, stableLazy) where
import Foreign.C.Types (CInt(..))
import Foreign.StablePtr
import GHC.Exts (Int(..), Int#, runRW#)
import GHC.IO (IO(..))

foreign import ccall unsafe "stable_store" store :: StablePtr a -> IO ()
foreign import ccall unsafe "stable_load" load :: IO (StablePtr a)
foreign import ccall unsafe "stable_equal" equal :: StablePtr a -> StablePtr a -> IO CInt
foreign import ccall unsafe "stable_clear" clear :: IO ()
foreign import capi unsafe "stable.h stable_identity" identity :: StablePtr a -> IO (StablePtr a)

run :: IO a -> a
run (IO f) = case runRW# f of (# _, value #) -> value

{-# NOINLINE stableRoundtrip #-}
stableRoundtrip :: Int# -> Int#
stableRoundtrip n = case run (do
  first <- newStablePtr (I# n, [11,23 :: Int])
  second <- newStablePtr (I# n, [11,23 :: Int])
  store first
  same <- equal first first
  different <- equal first second
  returned <- (load :: IO (StablePtr (Int, [Int]))) >>= identity
  (value, rest) <- deRefStablePtr returned
  clear
  freeStablePtr first
  freeStablePtr second
  pure (value + sum rest + fromIntegral same - fromIntegral different)) of I# result -> result

{-# NOINLINE stableLazy #-}
stableLazy :: Int# -> Int#
stableLazy n = case run (do
  pointer <- newStablePtr (error "C must not evaluate a StablePtr referent" :: Int)
  store pointer
  returned <- load >>= identity
  same <- equal pointer returned
  clear
  freeStablePtr pointer
  pure (I# n + fromIntegral same)) of I# result -> result
