-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE CPP, ForeignFunctionInterface, MagicHash, UnboxedTuples #-}
module Main (main) where
#include "ghcautoconf.h"

#if !USE_LIBDW
import Control.Monad (unless)
import Data.IORef (IORef, modifyIORef', newIORef, readIORef)
import Data.Word (Word8)
import Foreign.Marshal.Alloc (allocaBytes, mallocBytes)
import Foreign.Marshal.Array (peekArray)
import Foreign.Marshal.Utils (fillBytes)
import Foreign.Ptr (FunPtr, Ptr, castPtr, nullFunPtr)
import GHC.Exts (Int(I#), Weak#, addCFinalizerToWeak#, finalizeWeak#,
    mkWeak#, mkWeakNoFinalizer#, nullAddr#, touch#)
import GHC.IO (IO(..))
import GHC.Ptr (FunPtr(..), Ptr(..))

foreign import ccall unsafe "&libdwPoolRelease" poolRelease :: FunPtr (Ptr () -> IO ())
foreign import ccall unsafe "&backtraceFree" backtraceFree :: FunPtr (Ptr () -> IO ())
foreign import ccall unsafe "stdlib.h &free" freeFunction :: FunPtr (Ptr () -> IO ())
foreign import ccall unsafe "libdwPoolRelease" releaseNow :: Ptr () -> IO ()
foreign import ccall unsafe "backtraceFree" freeNow :: Ptr () -> IO ()

data WeakBox = WeakBox (Weak# ())

{-# NOINLINE make #-}
make :: IORef Int -> Maybe (IO ()) -> IO WeakBox
make key Nothing = IO $ \s -> case mkWeakNoFinalizer# key () s of
  (# s', weak #) -> (# s', WeakBox weak #)
make key (Just (IO action)) = IO $ \s -> case mkWeak# key () action s of
  (# s', weak #) -> (# s', WeakBox weak #)

{-# NOINLINE attach #-}
attach :: WeakBox -> FunPtr (Ptr () -> IO ()) -> Ptr () -> IO Int
attach (WeakBox weak) (FunPtr function) (Ptr pointer) = IO $ \s ->
  case addCFinalizerToWeak# function pointer 0# nullAddr# weak s of
    (# s', flag #) -> (# s', I# flag #)

{-# NOINLINE finalize #-}
finalize :: WeakBox -> IO (Int, IO ())
finalize (WeakBox weak) = IO $ \s -> case finalizeWeak# weak s of
  (# s', flag, action #) -> (# s', (I# flag, IO action) #)

touch :: a -> IO ()
touch value = IO $ \s -> case touch# value s of s' -> (# s', () #)
#endif

main :: IO ()
#if USE_LIBDW
main = fail "C finalizer oracle requires the selected GHC RTS USE_LIBDW=0 profile"
#else
main = allocaBytes 16 $ \bytes -> do
  let pointer = castPtr bytes
  fillBytes bytes 165 16
  -- The actual configured upstream bodies ignore even a non-session address.
  releaseNow pointer
  freeNow pointer
  directUnchanged <- (== replicate 16 (165 :: Word8)) <$> peekArray 16 bytes
  key <- newIORef 0
  plain <- make key Nothing
  first <- attach plain poolRelease pointer
  second <- attach plain backtraceFree pointer
  (plainFlag, _) <- finalize plain
  dead <- attach plain poolRelease pointer
  (again, _) <- finalize plain
  marker <- newIORef (0 :: Int)
  mixed <- make key (Just (modifyIORef' marker (+1)))
  mixedAdded <- attach mixed poolRelease pointer
  (mixedFlag, action) <- finalize mixed
  before <- readIORef marker
  unless (mixedFlag == 1) (fail "Missing original Haskell finalizer")
  action
  after <- readIORef marker
  mixedDead <- attach mixed backtraceFree pointer
  owned <- mallocBytes 8
  ownedWeak <- make key Nothing
  freeAdded <- attach ownedWeak freeFunction owned
  (ownedFlag, _) <- finalize ownedWeak
  freeDead <- attach ownedWeak freeFunction owned
  unchanged <- (== replicate 16 (165 :: Word8)) <$> peekArray 16 bytes
  touch key
  let observations = [poolRelease /= nullFunPtr, backtraceFree /= nullFunPtr,
        directUnchanged, first == 1, second == 1, plainFlag == 0, dead == 0,
        again == 0, mixedAdded == 1, mixedFlag == 1, before == 0, after == 1,
        mixedDead == 0, unchanged, freeFunction /= nullFunPtr,
        freeAdded == 1, ownedFlag == 0, freeDead == 0]
  unless (and observations) (fail (show observations))
  putStrLn "USE_LIBDW=0"
  print observations
#endif
