-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE ForeignFunctionInterface #-}

-- | Original GHC wrapper/dynamic declarations, including C retention between calls.
module DynamicCallback (run, makePointer, callPointer, unsafePointer, releasePointer) where

import Data.Int (Int32)
import Data.IORef
import Control.Exception (MaskingState(Unmasked), getMaskingState)
import Foreign.Ptr (FunPtr, Ptr, plusPtr, freeHaskellFunPtr)
import Foreign.Storable (peek, poke, pokeByteOff)

foreign import ccall "wrapper"
  wrap :: (Int32 -> IO Int32) -> IO (FunPtr (Int32 -> IO Int32))
foreign import ccall safe "dynamic"
  invoke :: FunPtr (Int32 -> IO Int32) -> Int32 -> IO Int32
foreign import ccall unsafe "thc_callback_retain"
  retain :: FunPtr (Int32 -> IO Int32) -> IO ()
foreign import ccall safe "thc_callback_invoke"
  invokeRetained :: Int32 -> IO Int32
foreign import ccall unsafe "thc_callback_clear"
  clear :: IO ()
foreign import ccall "&thc_callback_add"
  nativeAdd :: FunPtr (Int32 -> IO Int32)

foreign import ccall "wrapper"
  wrapPointer :: (Ptr Int32 -> IO (Ptr Int32)) -> IO (FunPtr (Ptr Int32 -> IO (Ptr Int32)))
foreign import ccall safe "dynamic"
  callPointer :: FunPtr (Ptr Int32 -> IO (Ptr Int32)) -> Ptr Int32 -> IO (Ptr Int32)
foreign import ccall unsafe "dynamic"
  unsafePointer :: FunPtr (Ptr Int32 -> IO (Ptr Int32)) -> Ptr Int32 -> IO (Ptr Int32)

{-# NOINLINE makePointer #-}
makePointer :: IO (FunPtr (Ptr Int32 -> IO (Ptr Int32)))
makePointer =
  wrapPointer $ \pointer -> do
    state <- getMaskingState
    value <- peek pointer
    poke pointer (value + 9)
    pokeByteOff pointer 4 (if state == Unmasked then 1 else 0 :: Int32)
    pure (pointer `plusPtr` 4)

{-# NOINLINE releasePointer #-}
releasePointer :: FunPtr (Ptr Int32 -> IO (Ptr Int32)) -> IO ()
releasePointer = freeHaskellFunPtr

{-# NOINLINE run #-}
run :: Int32 -> IO (Int32, Int32, Int, Int32)
run input = do
  effects <- newIORef (0 :: Int)
  callback <- wrap (\value -> modifyIORef' effects (+ 1) >> pure (value + 7))
  retain callback
  first <- invoke callback input
  second <- invokeRetained (input + 1)
  clear
  freeHaskellFunPtr callback
  count <- readIORef effects
  third <- invoke nativeAdd input
  pure (first, second, count, third)
