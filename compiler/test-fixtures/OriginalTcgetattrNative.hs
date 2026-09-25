-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
module Main where

import Control.Exception (bracket, finally)
import Control.Monad (forM)
import Data.Word (Word8)
import Foreign.C.Error (Errno(..), getErrno, resetErrno)
import Foreign.C.Types (CInt)
import Foreign.Marshal.Alloc (allocaBytes)
import Foreign.Marshal.Utils (fillBytes)
import Foreign.Ptr (castPtr, plusPtr)
import Foreign.Storable (peekByteOff)
import qualified GHC.Internal.System.Posix.Internals as P
import System.Environment (getArgs)
import System.IO (BufferMode(LineBuffering), hSetBuffering, isEOF, stdin, stdout)
import System.Posix.IO (closeFd, createPipe)
import System.Posix.Terminal (getTerminalName, openPseudoTerminal)
import System.Posix.Types (Fd(..))

observe :: CInt -> Int -> IO [Int]
observe fd fill = allocaBytes (size + 16) $ \storage -> do
  fillBytes storage 77 (size + 16)
  let image = castPtr (storage `plusPtr` 8)
  fillBytes image (fromIntegral fill) size
  resetErrno
  status <- P.c_tcgetattr fd image
  Errno errno <- getErrno
  bytes <- forM [0..size+15] $ \i -> fromIntegral <$> (peekByteOff storage i :: IO Word8)
  pure (fromIntegral status : fromIntegral errno : bytes)
  where size = P.sizeof_termios

main :: IO ()
main = bracket openPseudoTerminal (\(master, slave) -> closeFd slave `finally` closeFd master) $ \(_, slave@(Fd terminal)) -> do
  args <- getArgs
  if args == ["--serve"] then do
    -- Keep the private PTY alive while Kotlin compares the same terminal through
    -- its separately owned guest descriptor. Neither process changes its state.
    hSetBuffering stdout LineBuffering
    getTerminalName slave >>= print
    let loop = do
          done <- isEOF
          if done then pure () else do
            fill <- read <$> getLine
            observe terminal fill >>= print
            loop
    hSetBuffering stdin LineBuffering
    loop
  else bracket createPipe (\(readEnd, writeEnd) -> closeFd readEnd `finally` closeFd writeEnd) $ \(Fd pipe, _) -> do
    rows <- forM [(kind, fd, fill) | (kind, fd) <- [(0 :: Int,terminal),(1,pipe),(2,-1)], fill <- [0,90,165,255]] $ \(kind,fd,fill) -> do
      result <- observe fd fill
      pure (kind, fill, result)
    print (P.sizeof_termios, rows)
