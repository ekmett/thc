-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}
module Main where

import Control.Exception (bracket, evaluate)
import Control.Monad (forM)
import Foreign.C.Error (Errno(..), getErrno)
import GHC.Exts (Int(I#))
import GHC.Internal.System.Posix.Internals (c_close)
import System.Environment (getArgs)
import System.IO (SeekMode(SeekFromEnd))
import System.Posix.IO (OpenMode(..), closeFd, defaultFileFlags, fdSeek, openFd)
import System.Posix.Types (Fd(..))
import qualified OriginalFdReadyAudit as Original

main :: IO ()
main = do
  [privatePath] <- getArgs
  writeFile privatePath "keep"
  rows <- forM ["originalReadySafe", "originalReadyUnsafe"] $ \entry ->
    forM ["read", "write", "readwrite", "eof", "closed", "negative"] $ \scenario -> do
      let observe fd = forM [0,1 :: Int] $ \writing -> forM [0,1 :: Int] $ \socket ->
            forM (if scenario == "negative" then [0] else [-1,0,1,2147483649 :: Int]) $ \milliseconds -> do
              _ <- c_close (-1) -- Seed real EBADF; a successful poll must not clear it.
              Errno before <- getErrno
              let number = case fd of Fd raw -> fromIntegral raw :: Int
                  run (I# f) (I# w) (I# m) (I# s) = I# (case entry of
                    "originalReadySafe" -> Original.originalReadySafe f w m s
                    "originalReadyUnsafe" -> Original.originalReadyUnsafe f w m s
                    _ -> error "unknown native fdReady entry")
              result <- evaluate (run number writing milliseconds socket)
              Errno after <- getErrno
              pure (entry, scenario, writing, milliseconds, socket, result, fromIntegral before :: Int, fromIntegral after :: Int)
      case scenario of
        "negative" -> observe (Fd (-1))
        "closed" -> do
          fd <- openFd privatePath ReadOnly defaultFileFlags
          closeFd fd
          observe fd -- No intervening file opens may reuse this numeric descriptor.
        _ -> bracket (openFd privatePath (case scenario of
               "write" -> WriteOnly; "readwrite" -> ReadWrite; _ -> ReadOnly) defaultFileFlags)
             closeFd $ \fd -> do
               if scenario == "eof" then fdSeek fd SeekFromEnd 0 >> pure () else pure ()
               observe fd
  print (concatMap (concatMap (concatMap concat)) rows)
