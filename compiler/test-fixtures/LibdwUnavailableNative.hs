-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE CPP, ForeignFunctionInterface #-}
module Main (main) where
#include "ghcautoconf.h"

#if !USE_LIBDW
import Control.Monad (unless)
import Data.Maybe (isNothing)
import Data.Word (Word8)
import Foreign.C.Types (CInt(..))
import Foreign.Marshal.Alloc (allocaBytes)
import Foreign.Marshal.Array (peekArray)
import Foreign.Marshal.Utils (fillBytes)
import Foreign.Ptr (Ptr, castPtr, nullPtr)
import GHC.Internal.ExecutionStack.Internal (collectStackTrace, invalidateDebugCache)

foreign import ccall unsafe "libdwPoolTake" poolTake :: IO (Ptr ())
foreign import ccall unsafe "libdwGetBacktrace" backtrace :: Ptr () -> IO (Ptr ())
foreign import ccall unsafe "libdwLookupLocation" lookupLocation :: Ptr () -> Ptr Word8 -> Ptr () -> IO CInt
foreign import ccall unsafe "libdwPoolClear" poolClear :: IO ()
#endif

main :: IO ()
#if USE_LIBDW
main = fail "libdw-unavailable oracle requires the selected GHC RTS USE_LIBDW=0 profile"
#else
main = allocaBytes 64 $ \location -> do
  fillBytes location 165 64
  session <- poolTake
  trace <- backtrace nullPtr
  failed <- lookupLocation nullPtr location nullPtr
  unchanged <- (== replicate 64 165) <$> peekArray 64 location
  -- These ignored addresses are intentionally not native libdw objects. The
  -- disabled backend must neither dereference them nor mutate the buffer.
  trace2 <- backtrace (castPtr location)
  failed2 <- lookupLocation (castPtr location) location (castPtr location)
  unchanged2 <- (== replicate 64 165) <$> peekArray 64 location
  poolClear
  original <- collectStackTrace
  invalidateDebugCache
  let observations = [session == nullPtr, trace == nullPtr, failed == 1, unchanged,
        trace2 == nullPtr, failed2 == 1, unchanged2, isNothing original]
  unless (and observations) (fail (show observations))
  putStrLn "USE_LIBDW=0"
  print observations
#endif
