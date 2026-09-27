-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE CApiFFI #-}
module Main where

import Control.Monad (forM)
import Foreign.C.Types (CInt(..))
import Foreign.Marshal.Alloc (allocaBytes)
import GHC.Internal.Foreign.C.Error (Errno(..), getErrno, resetErrno)
import qualified GHC.Internal.System.Posix.Internals as P
import System.Environment (getArgs)

-- This native-only oracle calls the original installed header directly for
-- nonzero inputs; the guest export above comes from resetErrno's own unfolding.
foreign import capi unsafe "HsBase.h __hscore_set_errno"
  setNativeErrno :: CInt -> IO ()
foreign import capi unsafe "HsBase.h __hscore_get_errno"
  getNativeErrno :: IO CInt

main :: IO ()
main = do
  [resultPath] <- getArgs
  rows <- allocaBytes 1 $ \buffer -> forM values $ \value -> do
    setNativeErrno value
    roundTrip <- getNativeErrno
    successResult <- P.c_write 1 buffer 0
    successErrno <- getNativeErrno
    failureResult <- P.c_write (-1) buffer 0
    failureErrno <- getNativeErrno
    resetErrno
    Errno cleared <- getErrno
    pure [fromIntegral value, fromIntegral roundTrip, fromIntegral successResult,
          fromIntegral successErrno, fromIntegral failureResult,
          fromIntegral failureErrno, fromIntegral cleared :: Integer]
  writeFile resultPath (show rows ++ "\n")
  where
    values = [-2147483648, -1, 0, 1, 2147483647] :: [CInt]
