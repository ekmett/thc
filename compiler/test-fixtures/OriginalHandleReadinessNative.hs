-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

module Main where

import Control.Exception (bracket)
import GHC.Internal.Foreign.C.Error (Errno(..), getErrno)
import GHC.Internal.Foreign.C.Types (CInt)
import GHC.Internal.System.Posix.Internals (c_isatty)
import System.Environment (getArgs)
import System.IO (hFlush, stdout)
import System.Posix.IO (closeFd)
import System.Posix.Terminal (getSlaveTerminalName, openPseudoTerminal)

main :: IO ()
main = do
  arguments <- getArgs
  case arguments of
    ["--hold-pty"] -> bracket openPseudoTerminal
      (\(master, slave) -> closeFd slave >> closeFd master) $ \(master, slave) -> do
        path <- getSlaveTerminalName master
        result <- c_isatty (fromIntegral slave)
        putStrLn (path ++ "\t" ++ show (fromIntegral result :: Int))
        hFlush stdout
        _ <- getLine -- Keep both PTY ends live until the JVM has completed its query.
        pure ()
    [fd] -> do
      result <- c_isatty (fromIntegral (read fd :: Int))
      Errno errorCode <- getErrno
      print (fromIntegral result :: Int, if result == (0 :: CInt) then fromIntegral errorCode :: Int else 0)
    _ -> fail "Expected one descriptor or --hold-pty"
