-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE ForeignFunctionInterface #-}
module Main (main) where

import Control.Exception (bracket)
import Foreign.C.Types (CInt(..))
import Foreign.Ptr (Ptr, nullPtr)

foreign import ccall unsafe "stg_sig_install"
  install :: CInt -> CInt -> Ptr () -> IO CInt

-- Native GHC has already installed TopHandler's RST action before main.
-- Normalize to DFL for the comparison and restore that actual prior action.
-- No OS signal is delivered by this oracle.
main :: IO ()
main = do
  rows <- mapM check [1, 2, 3, 15]
  print rows
  where
    check signal = bracket (install signal (-1) nullPtr)
      (\old -> install signal old nullPtr >> pure ()) $ \_ -> do
        previous <- mapM (\action -> install signal action nullPtr) [-2, -4, -5, -1]
        pure (fromIntegral signal :: Int, map fromIntegral previous :: [Int])
