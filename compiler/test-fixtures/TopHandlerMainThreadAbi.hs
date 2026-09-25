-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE ForeignFunctionInterface, MagicHash, UnliftedFFITypes #-}
module TopHandlerMainThreadAbi (registerCurrent) where

import GHC.Internal.Conc.Sync (ThreadId, mkWeakThreadId, myThreadId)
import GHC.Internal.Weak (Weak(..))
import GHC.Prim (Weak#)

-- Typecheck the exact foreign ABI used by GHC.Internal.TopHandler.runMainIO1.
-- The genuine installed Core, not this test declaration, is the runtime input.
foreign import ccall unsafe "rts_setMainThread"
  setMainThread :: Weak# ThreadId -> IO ()

registerCurrent :: IO (Weak ThreadId)
registerCurrent = do
  thread <- myThreadId
  weak <- mkWeakThreadId thread
  case weak of
    Weak carrier -> setMainThread carrier
  pure weak
