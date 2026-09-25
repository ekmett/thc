-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples #-}
module Main where

import Control.Exception (try)
import GHC.IO (IO(..))
import GHC.IO.Exception (IOErrorType(InvalidArgument))
import GHC.Types (Int(I#))
import System.IO.Error (ioeGetLocation, ioeGetErrorType)
import System.Posix.IO (createPipe, closeFd, fdWrite)
import System.Posix.Types (Fd(..))
import qualified FileWaitAudit as Audit

readReady, writeReady :: Int -> IO ()
readReady (I# fd) = IO (\s -> case Audit.waitReadRoot fd s of next -> (# next, () #))
writeReady (I# fd) = IO (\s -> case Audit.waitWriteRoot fd s of next -> (# next, () #))

main :: IO ()
main = do
  (readEnd, writeEnd) <- createPipe
  let Fd readNumber = readEnd
      Fd writeNumber = writeEnd
  _ <- fdWrite writeEnd "x"
  readReady (fromIntegral readNumber)
  writeReady (fromIntegral writeNumber)
  closeFd readEnd
  bad <- try (readReady (fromIntegral readNumber)) :: IO (Either IOError ())
  case bad of
    Left errorValue | ioeGetLocation errorValue == "awaitEvent"
                    , ioeGetErrorType errorValue == InvalidArgument -> pure ()
    _ -> error "waitRead# did not raise GHC's original bad-FD error"
  closeFd writeEnd
  putStrLn "read-ready\nwrite-ready\noriginal-bad-fd"
