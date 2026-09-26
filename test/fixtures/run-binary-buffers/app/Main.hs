-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

module Main (main) where

import Control.Exception (IOException, bracket, try)
import Control.Monad (forM_, unless)
import Data.Word (Word8)
import Foreign.Marshal.Alloc (free, mallocBytes)
import Foreign.Ptr (Ptr, plusPtr)
import Foreign.Storable (peekByteOff, pokeByteOff)
import System.IO
  (BufferMode (NoBuffering), IOMode (ReadMode, WriteMode), hGetBuf, hPutBuf,
   hSetBuffering, withBinaryFile)
import System.IO.Error (ioeGetErrorString)

main :: IO ()
main = do
  outcome <- try exercise :: IO (Either IOException ())
  case outcome of
    Left problem -> unless (ioeGetErrorString problem == "expected buffer cleanup") (ioError problem)
    Right () -> fail "buffer cleanup probe did not throw"
  -- No newline: original executable shutdown must flush this final message.
  putStr "binary buffers ok"
  where
    exercise = bracket (mallocBytes 16 :: IO (Ptr Word8))
      (\buffer -> free buffer >> putStrLn "buffer freed") $ \buffer -> do
        let payload = [0x00, 0x7f, 0x80, 0xff, 0x01, 0xfe] :: [Word8]
            sentinel = 0xa5 :: Word8
            reset = forM_ [0..15 :: Int] $ \offset -> pokeByteOff buffer offset sentinel
        reset
        forM_ (zip [1..] payload) $ \(offset, byte) -> pokeByteOff buffer offset byte
        withBinaryFile "buffers.bin" WriteMode $ \handle -> do
          hSetBuffering handle NoBuffering
          hPutBuf handle (buffer `plusPtr` 1) 4
          hPutBuf handle (buffer `plusPtr` 5) 2
        written <- mapM (peekByteOff buffer) [0..15 :: Int] :: IO [Word8]
        unless (written == sentinel : payload ++ replicate 9 sentinel)
          (fail "binary buffer write modified its source")
        reset
        withBinaryFile "buffers.bin" ReadMode $ \handle -> do
          hSetBuffering handle NoBuffering
          first <- hGetBuf handle (buffer `plusPtr` 3) 4
          short <- hGetBuf handle (buffer `plusPtr` 8) 8
          eof <- hGetBuf handle (buffer `plusPtr` 12) 3
          empty <- hGetBuf handle (buffer `plusPtr` 16) 0
          unless ([first, short, eof, empty] == [4, 2, 0, 0])
            (fail "binary buffer read counts differ")
        bytes <- mapM (peekByteOff buffer) [0..15 :: Int] :: IO [Word8]
        unless (bytes == [sentinel, sentinel, sentinel, 0x00, 0x7f, 0x80, 0xff,
                          sentinel, 0x01, 0xfe, sentinel, sentinel, sentinel, sentinel, sentinel, sentinel])
          (fail "binary buffer bytes or untouched sentinels differ")
        -- The exception must cross bracket's original free finalizer before it
        -- reaches the handler above. Other IOExceptions must still fail main.
        ioError (userError "expected buffer cleanup")
