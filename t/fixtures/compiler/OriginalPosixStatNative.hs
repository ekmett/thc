-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}

-- |
-- Module      : Main
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : POSIX; depends on the unix package
--
-- Native GHC observer for the original posix stat fixture.
module Main where

import Control.Exception (evaluate)
import Control.Monad (unless, void)
import qualified Data.ByteString as BS
import Data.Bits ((.&.))
import Data.Word (Word8)
import Foreign.Marshal.Alloc (allocaBytes)
import Foreign.Marshal.Utils (fillBytes)
import Foreign.Storable (peekByteOff)
import GHC.Exts (Int(I#))
import GHC.Ptr (Ptr(..), plusPtr)
import GHC.Internal.Foreign.C.Error (Errno(..), getErrno)
import GHC.Internal.Foreign.C.String (withCString)
import qualified GHC.Internal.System.Posix.Internals as P
import OriginalPosixStatAudit
import System.Environment (getArgs)
import System.Directory (renameFile, removeFile)

main :: IO ()
main = do
  [directory] <- getArgs
  fstats <- descriptorObservations directory
  print (P.sizeof_stat, fstats)

descriptorObservations :: FilePath -> IO [(String, Int, Int, [Int], Bool)]
descriptorObservations directory = do
  let path = directory ++ "/fstat.bin"
      renamed = directory ++ "/fstat-renamed.bin"
  BS.writeFile path (BS.pack [0..255])
  withCString path $ \name -> do
    void (P.c_chmod name 0o600)
    fd <- P.c_safe_open name P.o_RDWR 0
    unless (fd >= 0) (fail "native fstat open failed")
    alias <- P.c_dup fd
    unless (alias >= 0) (fail "native fixture dup failed")
    allocaBytes P.sizeof_stat $ \initial -> do
      initialStatus <- P.c_fstat fd initial
      unless (initialStatus == 0) (fail "native initial fstat failed")
      dev <- P.st_dev initial
      ino <- P.st_ino initial
      let record label source = allocaBytes (P.sizeof_stat + 16) $ \storage -> do
            fillBytes storage 90 (P.sizeof_stat + 16)
            let buffer = storage `plusPtr` 8
            void (P.c_close (-1)) -- Genuine native sticky EBADF, including success.
            status <- evaluate (case fromIntegral source :: Int of { I# raw ->
              case buffer of { Ptr address -> I# (originalFstat raw address) } })
            Errno captured <- getErrno
            errno <- evaluate (case fromIntegral source :: Int of { I# raw ->
              case buffer of { Ptr address -> I# (originalFstatErrno raw address) } })
            unless (errno == fromIntegral captured) (fail "original fstat errno observer changed the observation")
            bytes <- mapM (peekByteOff storage) [0..P.sizeof_stat+15] :: IO [Word8]
            values <- if status == 0 then do
              size <- P.st_size buffer; mode <- P.st_mode buffer
              device <- P.st_dev buffer; inode <- P.st_ino buffer
              pure [fromIntegral size, fromIntegral (P.c_s_isreg mode), fromEnum (device == dev), fromEnum (inode == ino),
                fromIntegral mode .&. 0o777]
              else pure []
            pure (label, status, errno, values,
              if status == 0 then all (== 90) (take 8 bytes ++ drop (P.sizeof_stat + 8) bytes)
              else all (== 90) bytes)
      initialRow <- record "initial" fd
      changed <- P.c_ftruncate fd 17
      unless (changed == 0) (fail "native fixture truncate failed")
      resized <- record "resized" fd
      permissions <- P.c_chmod name 0o400
      unless (permissions == 0) (fail "native fixture chmod failed")
      chmod <- record "chmod" fd
      renameFile path renamed
      BS.writeFile path (BS.pack [42])
      moved <- record "renamed" alias
      removeFile renamed
      unlinked <- record "unlinked" fd
      invalid <- record "invalid" (-1)
      void (P.c_close alias)
      void (P.c_close fd)
      closed <- record "closed" fd
      removeFile path
      pure [initialRow,resized,chmod,moved,unlinked,invalid,closed]
