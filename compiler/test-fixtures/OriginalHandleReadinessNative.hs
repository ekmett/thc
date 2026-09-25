-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

module Main where

import GHC.Internal.Foreign.C.Error (Errno(..), getErrno)
import GHC.Internal.Foreign.C.Types (CInt)
import GHC.Internal.System.Posix.Internals (c_isatty)
import System.Environment (getArgs)

main :: IO ()
main = do
  [fd] <- getArgs
  result <- c_isatty (fromIntegral (read fd :: Int))
  Errno errorCode <- getErrno
  print (fromIntegral result :: Int, if result == (0 :: CInt) then fromIntegral errorCode :: Int else 0)
