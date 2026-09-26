-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
module Main (main) where

import Control.Exception (IOException, bracket, try)
import Control.Monad (unless)
import System.Directory (getTemporaryDirectory, removeFile)
import System.Environment (getArgs, getExecutablePath)
import System.Exit (ExitCode(ExitSuccess))
import System.IO (BufferMode(LineBuffering), hClose, hFlush, hGetLine, hPutStrLn,
                  hSetBuffering, hWaitForInput, openTempFile, stdout)
import System.Process (CreateProcess(..), StdStream(CreatePipe), proc, withCreateProcess, waitForProcess)
import System.Timeout (timeout)
import THC.Driver.Lock (withLock)

check :: Bool -> String -> IO ()
check condition message = unless condition (fail message)

main :: IO ()
main = do
  args <- getArgs
  case args of
    ["--child", path] -> do
      hSetBuffering stdout LineBuffering
      _ <- getLine
      putStrLn "attempting"
      withLock path (putStrLn "acquired")
    [] -> do
      temporary <- getTemporaryDirectory
      bracket (openTempFile temporary "thc-driver-lock-") (removeFile . fst) $ \(path, handle) -> do
        hClose handle
        -- Exception cleanup must release the same native lock.
        result <- try (withLock path (fail "deliberate lock exception")) :: IO (Either IOException ())
        check (either (const True) (const False) result) "exception was swallowed"
        executable <- getExecutablePath
        withCreateProcess ((proc executable ["--child", path])
          {std_in = CreatePipe, std_out = CreatePipe}) $ \input output _ child ->
            case (input, output) of
              (Just writer, Just reader) -> do
                withLock path $ do
                  hPutStrLn writer "start"
                  hFlush writer
                  started <- timeout 5000000 (hGetLine reader)
                  check (started == Just "attempting") "child did not attempt the lock"
                  ready <- hWaitForInput reader 200
                  check (not ready) "another process entered the locked region"
                acquired <- timeout 5000000 (hGetLine reader)
                check (acquired == Just "acquired") "lock was not released on scope exit"
                resultCode <- timeout 5000000 (waitForProcess child)
                check (resultCode == Just ExitSuccess) "lock child did not finish successfully"
              _ -> fail "missing child pipes"
        putStrLn "PASS native driver lock: exclusion, scope exit, exception cleanup"
    _ -> fail "usage: driver-lock-tests [--child PATH]"
