-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}
module Main where

import Control.Exception (evaluate, bracket_)
import Control.Monad (forM, unless, void, when)
import qualified Data.ByteString as BS
import Data.Bits ((.&.), (.|.))
import Data.Word (Word8)
import Foreign.Marshal.Alloc (allocaBytes)
import Foreign.Marshal.Array (withArray0)
import Foreign.Ptr (castPtr)
import Foreign.Storable (peekByteOff, pokeByteOff)
import GHC.Exts (Int(I#), Word(W#))
import GHC.Ptr (Ptr(..))
import GHC.Internal.Foreign.C.Error (Errno(..), getErrno)
import qualified GHC.Internal.System.Posix.Internals as P
import OriginalOpenAudit
import OriginalOpenRequestNative (checkOpenRequests)
import System.Directory (createDirectoryIfMissing, getCurrentDirectory, setCurrentDirectory, createDirectoryLink)
import System.Environment (getArgs)
import System.Posix.Files (setFileCreationMask)

main :: IO ()
main = do
  [directory] <- getArgs
  createDirectoryIfMissing True (directory ++ "/sub/child")
  createDirectoryLink (directory ++ "/sub/child") (directory ++ "/link")
  checkOpenRequests directory
  old <- getCurrentDirectory
  mask <- setFileCreationMask 0o022
  variants <- forM [originalOpen, originalOpenSafe, originalOpenInterruptible] $ \openCall -> bracket_ (setCurrentDirectory directory >> void (setFileCreationMask 0o022)) (setCurrentDirectory old >> void (setFileCreationMask mask)) $ do
    let specs = [("read", [102], P.o_RDONLY, 0, True, False),
                 ("rw", [102], P.o_RDWR, 0, True, True),
                 ("truncate", [102], P.o_WRONLY .|. P.o_TRUNC, 0, True, True),
                 ("append", [102], P.o_WRONLY .|. P.o_APPEND, 0, True, True),
                 ("create", [102], P.o_RDWR .|. P.o_CREAT, 0o600, False, True),
                 ("exclusive", [102], P.o_RDWR .|. P.o_CREAT .|. P.o_EXCL, 0o600, True, False),
                 ("absent", [102], P.o_RDONLY, 0, False, False),
                 ("empty", [], P.o_RDONLY, 0, False, False),
                 ("high-mode", [102], P.o_RDWR .|. P.o_CREAT, 0x800001ff, False, True),
                 ("invalid-byte", [110,255], P.o_RDWR .|. P.o_CREAT, 0o600, False, True),
                 ("relative-invalid", [110,254], P.o_RDWR .|. P.o_CREAT, 0o600, False, True),
                 ("dot-symlink", map (fromIntegral . fromEnum) "link/../dot", P.o_RDWR .|. P.o_CREAT, 0o600, False, True),
                 ("directory", [115,117,98], P.o_RDONLY, 0, False, False)]
    forM specs $ \(label, suffix, flags, mode, existing, writing) -> do
      let relative = label `elem` ["relative-invalid", "dot-symlink", "empty"]
          prefix = if relative then [] else map (fromIntegral . fromEnum) (directory ++ "/")
          bytes = prefix ++ suffix :: [Word8]
      when existing $ BS.writeFile "f" (BS.pack [10,20])
      when existing $ withArray0 0 bytes $ \p -> void (P.c_chmod (castPtr p) 0o600)
      withArray0 0 bytes $ \pointer -> do
        void (P.c_close (-1))
        result <- evaluate (case (castPtr pointer, fromIntegral flags :: Int, mode :: Word) of
          (Ptr path, I# raw, W# permissions) -> I# (openCall path raw permissions))
        Errno errno <- getErrno -- Capture before fstat, printing or any other IO.
        values <- if result < 0 then pure [] else do
          when writing $ allocaBytes 1 $ \buffer -> do
            pokeByteOff buffer 0 (99 :: Word8)
            written <- P.c_write (fromIntegral result) buffer 1
            unless (written == 1) (fail "native original-open write failed")
          allocaBytes P.sizeof_stat $ \image -> do
            status <- P.c_fstat (fromIntegral result) image
            unless (status == 0) (fail "native original-open fstat failed")
            size <- P.st_size image; permissions <- P.st_mode image
            pure [fromIntegral size, fromIntegral permissions .&. 0o777,
              fromIntegral (P.c_s_isreg permissions), fromIntegral (P.c_s_isdir permissions)]
        when (result >= 0) $ void (P.c_close (fromIntegral result))
        contents <- if result >= 0 && label /= "directory" then do
          fd <- P.c_open (castPtr pointer) P.o_RDONLY 0
          unless (fd >= 0) (fail "native observation reopen failed")
          observed <- allocaBytes 8 $ \buffer -> do
            count <- P.c_read fd buffer 8
            unless (count >= 0) (fail "native observation read failed")
            mapM (peekByteOff buffer) [0..fromIntegral count-1] :: IO [Word8]
          void (P.c_close fd)
          pure observed
          else pure []
        when (label /= "directory" && not (null bytes)) $ void (P.c_unlink (castPtr pointer))
        pure (label, suffix, relative, fromIntegral flags :: Int, mode,
          if result < 0 then -1 else 0 :: Int, fromIntegral errno :: Int, values :: [Int], contents, writing)
  case variants of
    rows : others | all (== rows) others -> print rows
    _ -> fail "Original unsafe/safe/interruptible open observations disagree"
