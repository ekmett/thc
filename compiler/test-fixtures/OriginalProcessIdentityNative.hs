-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE ForeignFunctionInterface #-}
module Main where

import Foreign.C.Types (CInt(..), CUInt(..))
import System.Environment (getArgs)

-- Independent live native observations. The JVM is a different process and
-- must compare its PID against a same-process authority, not this child PID.
foreign import ccall unsafe "unistd.h getpid" nativePid :: IO CInt
foreign import ccall unsafe "unistd.h getppid" nativeParentPid :: IO CInt
foreign import ccall unsafe "unistd.h geteuid" nativeEuid :: IO CUInt

main :: IO ()
main = do
  [resultPath] <- getArgs
  pid <- nativePid
  parentPid <- nativeParentPid
  euid <- nativeEuid
  writeFile resultPath (show [fromIntegral pid, fromIntegral parentPid, fromIntegral euid :: Integer] ++ "\n")
