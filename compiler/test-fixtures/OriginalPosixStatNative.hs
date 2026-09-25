-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}
module Main where

import Control.Monad (forM, forM_)
import qualified Data.ByteString as BS
import Data.Word (Word8)
import Foreign.Marshal.Alloc (allocaBytes)
import Foreign.Marshal.Utils (fillBytes)
import Foreign.Storable (peekByteOff, pokeByteOff)
import GHC.Exts (Int(I#))
import GHC.Internal.Foreign.C.String (withCString)
import qualified GHC.Internal.System.Posix.Internals as P
import OriginalPosixStatAudit
import System.Environment (getArgs)

main :: IO ()
main = do
  [directory] <- getArgs
  let path = directory ++ "/sample.bin"
  BS.writeFile path (BS.pack [0..255])
  withCString path $ \name -> do
    fd <- P.c_safe_open name 0 0
    if fd < 0 then fail "native open failed" else pure ()
    allocaBytes P.sizeof_stat $ \buffer -> do
      -- Initialise padding so binary evidence never discloses uninitialised memory.
      fillBytes buffer 0 P.sizeof_stat
      status <- P.c_fstat fd buffer
      if status /= 0 then fail "native fstat failed" else pure ()
      real <- observe buffer
      artificial <- forM [0,1,127,128,255 :: Int] $ \seed -> do
        forM_ [0..P.sizeof_stat-1] $ \index ->
          pokeByteOff buffer index (fromIntegral (seed + 37*index) :: Word8)
        observe buffer
      let modes = [0..65535] ++ [-1,2147483647,2147483648,4294967295]
          classifications = [(mode, case mode of I# raw -> I# (originalStatTypes raw)) | mode <- modes]
      print (I# (originalStatSize 0#), real : artificial, classifications)
    result <- P.c_close fd
    if result /= 0 then fail "native close failed" else pure ()
  where
    observe buffer = do
      bytes <- mapM (peekByteOff buffer) [0..P.sizeof_stat-1] :: IO [Word8]
      dev <- P.st_dev buffer
      ino <- P.st_ino buffer
      mode <- P.st_mode buffer
      size <- P.st_size buffer
      pure (bytes, [fromIntegral dev :: Int, fromIntegral ino, fromIntegral mode, fromIntegral size])
