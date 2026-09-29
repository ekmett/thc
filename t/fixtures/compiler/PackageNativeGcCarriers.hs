-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnliftedFFITypes #-}

-- Genuine GC carriers: these imports must retain nominal provenance, never
-- become scalar native adapters. The oracle supplies only GHC-owned closures.
module PackageNativeGcCarriers where

import Control.Concurrent (forkIO, newEmptyMVar, putMVar, takeMVar)
import Foreign.C.Types
import GHC.Exts (ThreadId#, Weak#)
import GHC.Internal.Conc.Sync (ThreadId(..), myThreadId, mkWeakThreadId)
import GHC.Internal.Weak (Weak(..))

foreign import ccall unsafe "rts_getThreadId" threadNumber :: ThreadId# -> CULLong
foreign import ccall unsafe "eq_thread" threadEqual :: ThreadId# -> ThreadId# -> CBool
foreign import ccall unsafe "cmp_thread" threadCompare :: ThreadId# -> ThreadId# -> CInt
foreign import ccall unsafe "rts_enableThreadAllocationLimit" enableLimit :: ThreadId# -> IO ()
foreign import ccall unsafe "rts_disableThreadAllocationLimit" disableLimit :: ThreadId# -> IO ()
foreign import ccall unsafe "rts_setMainThread" setMainThread :: Weak# ThreadId -> IO ()
foreign import ccall unsafe "reportStackOverflow" reportOverflow :: ThreadId# -> IO ()
foreign import ccall unsafe "getpid" ordinaryScalar :: IO CInt

main :: IO ()
main = do
  own@(ThreadId own#) <- myThreadId
  ready <- newEmptyMVar
  release <- newEmptyMVar
  finished <- newEmptyMVar
  _ <- forkIO $ myThreadId >>= putMVar ready >> takeMVar release >> putMVar finished ()
  ThreadId other# <- takeMVar ready
  print (threadEqual own# own# /= 0)
  print (threadEqual own# other# == 0)
  print (threadCompare own# own# == 0)
  print (signum (threadCompare own# other#) == negate (signum (threadCompare other# own#)))
  print (threadNumber own# /= threadNumber other#)
  enableLimit own#
  disableLimit own#
  Weak weak# <- mkWeakThreadId own
  setMainThread weak#
  print =<< ((> 0) <$> ordinaryScalar)
  putMVar release ()
  takeMVar finished
