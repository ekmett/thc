-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE CPP #-}
module Main (main) where

import System.Environment (getArgs)
import System.Exit (die)

#if defined(mingw32_HOST_OS)
import Data.Word (Word8)
import Foreign.C.Error (Errno(..), getErrno)
import Foreign.C.String (CWString, withCWString)
import Foreign.Marshal.Alloc (allocaBytes)
import Foreign.Storable (peekByteOff)
import qualified GHC.Internal.System.Posix.Internals as Posix

main :: IO ()
main = do
  arguments <- getArgs
  case arguments of
    [name] -> withCWString name observe
    _ -> die "Usage: WindowsFileOracle PATH (three bytes: 37,91,122)"

observe :: CWString -> IO ()
observe path = do
  descriptor <- Posix.c_open path 0 0
  Errno code <- getErrno
  putStrLn ("native-open=" ++ show descriptor ++ ", errno=" ++ show code)
  if descriptor < 0 then die "Native Windows open failed" else do
    allocaBytes 8 $ \buffer -> do
      zero <- Posix.c_safe_read descriptor buffer 0
      partial <- Posix.c_safe_read descriptor buffer 5
      values <- mapM (\index -> peekByteOff buffer index :: IO Word8) [0,1,2]
      eof <- Posix.c_safe_read descriptor buffer 1
      failed <- Posix.c_safe_write descriptor buffer 1
      Errno failure <- getErrno
      print (zero,partial,values,eof,failed,failure)
    closed <- Posix.c_close descriptor
    putStrLn ("native-close=" ++ show closed)
#else
main :: IO ()
main = die "WindowsFileOracle requires native Windows GHC"
#endif
