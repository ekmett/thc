-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
module Main where
import Data.IORef
import Foreign.StablePtr
import ForeignExportRegistration (callbackAgain)

main :: IO ()
main = do
  ref <- newIORef (0 :: Int)
  pointer <- newStablePtr (modifyIORef' ref (+ 7))
  callbackAgain pointer
  freeStablePtr pointer
  readIORef ref >>= print
