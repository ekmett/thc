-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE ForeignFunctionInterface #-}
module Main (main) where

import Foreign.C.Types (CInt(..))
import System.Environment (getArgs)
import System.Posix.Signals (sigTERM, sigSTOP)

-- Exact original TopHandler declarations, calling the linked RTS implementation.
foreign import ccall "Rts.h shutdownHaskellAndExit"
  shutdownExit :: CInt -> CInt -> IO ()
foreign import ccall "shutdownHaskellAndSignal"
  shutdownSignal :: CInt -> CInt -> IO ()

main :: IO ()
main = do
  args <- getArgs
  case args of
    ["--signals"] -> print (sigTERM, sigSTOP)
    [kind, code, fast] -> do
      (case kind of
        "exit" -> shutdownExit
        "signal" -> shutdownSignal
        _ -> error "Unknown shutdown operation") (read code) (read fast)
      error "Original shutdown unexpectedly returned"
    _ -> error "Expected kind, code and fast flag"
