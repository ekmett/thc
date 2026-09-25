-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}
module Main where

import Control.Exception (bracket)
import Control.Monad (forM)
import Data.Bits ((.|.), (.&.), complement, shiftL)
import GHC.Exts (Int(I#))
import GHC.Internal.Foreign.C.Error (Errno(..), getErrno)
import qualified GHC.Internal.System.Posix.Internals as P
import OriginalFcntlAudit
import System.Environment (getArgs)
import System.Posix.IO (OpenMode(ReadWrite), closeFd, defaultFileFlags, dup, openFd)
import System.Posix.Types (Fd(..))

main :: IO ()
main = do
  [path] <- getArgs
  writeFile path "abc"
  let constants = [I# (originalAppend 0#), I# (originalCreat 0#), I# (originalNoctty 0#),
        I# (originalNonblock 0#), I# (originalRdonly 0#), I# (originalRdwr 0#),
        I# (originalWronly 0#), I# (originalGetfl 0#), I# (originalSetfl 0#)]
  -- IO sequencing is real: repeated runRW observers must not be CSE'd by GHC.
  rows <- bracket (openFd path ReadWrite defaultFileFlags) closeFd $ \fd@(Fd number) ->
    bracket (dup fd) closeFd $ \(Fd alias) -> do
      initial <- P.c_fcntl_read number P.const_f_getfl
      let base = fromIntegral initial :: Integer
          append = fromIntegral P.o_APPEND
          nonblock = fromIntegral P.o_NONBLOCK
          flags = [base .|. nonblock, base .|. append, base .&. complement (append .|. nonblock),
                   base .|. nonblock .|. (1 `shiftL` 40)]
      forM flags $ \value -> do
        result <- P.c_fcntl_write number P.const_f_setfl (fromIntegral value)
        observed <- P.c_fcntl_read alias P.const_f_getfl
        pure [value, fromIntegral result, fromIntegral observed]
  invalid <- P.c_fcntl_read (-1) P.const_f_getfl
  Errno errorNumber <- getErrno
  print (constants, rows, [fromIntegral invalid, fromIntegral errorNumber] :: [Integer])
