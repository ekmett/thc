-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
module Main where

import Control.Exception (bracket, finally)
import Control.Monad (forM, unless)
import Data.Bits ((.&.), (.|.), complement)
import Data.Word (Word8)
import Foreign.C.Error (Errno(..), getErrno, resetErrno, throwErrnoIfMinus1_)
import Foreign.C.Types (CInt)
import Foreign.Marshal.Alloc (allocaBytes)
import Foreign.Marshal.Utils (copyBytes, fillBytes)
import Foreign.Ptr (Ptr, castPtr, plusPtr)
import Foreign.Storable (peekByteOff)
import qualified GHC.Internal.System.Posix.Internals as P
import System.Environment (getArgs)
import System.IO (BufferMode(LineBuffering), hSetBuffering, isEOF, stdout)
import System.Posix.IO (closeFd, createPipe)
import System.Posix.Terminal (getTerminalName, openPseudoTerminal)
import System.Posix.Types (Fd(..))

size :: Int
size = P.sizeof_termios

bytes :: Ptr a -> IO [Int]
bytes storage = forM [0..size+15] $ \i -> fromIntegral <$> (peekByteOff storage i :: IO Word8)

image :: (Ptr a -> Ptr P.CTermios -> IO b) -> IO b
image body = allocaBytes (size + 16) $ \storage -> do
  fillBytes storage 77 (size + 16)
  let pointer = castPtr (storage `plusPtr` 8)
  fillBytes pointer 90 size
  body storage pointer

observe :: CInt -> Ptr P.CTermios -> CInt -> CInt -> Bool -> IO [Int]
observe terminal original descriptor action echo = do
  let restore = throwErrnoIfMinus1_ "restore private PTY" (P.c_tcsetattr terminal P.const_tcsanow original)
  restore
  flip finally restore $ image $ \storage input -> do
    copyBytes input original size
    flags <- P.c_lflag input
    P.poke_c_lflag input (if echo then flags .|. fromIntegral P.const_echo else flags .&. complement (fromIntegral P.const_echo))
    before <- bytes storage
    resetErrno
    status <- P.c_tcsetattr descriptor action input
    Errno errno <- getErrno
    supplied <- bytes storage
    unless (before == supplied) (fail "tcsetattr mutated its input image")
    image $ \output current -> do
      throwErrnoIfMinus1_ "read private PTY" (P.c_tcgetattr terminal current)
      observed <- bytes output
      pure (fromIntegral status : fromIntegral errno : supplied ++ observed)

main :: IO ()
main = bracket openPseudoTerminal (\(master, slave) -> closeFd slave `finally` closeFd master) $ \(_, slave@(Fd terminal)) ->
  image $ \_ original -> do
    throwErrnoIfMinus1_ "save private PTY" (P.c_tcgetattr terminal original)
    let restore = throwErrnoIfMinus1_ "restore private PTY" (P.c_tcsetattr terminal P.const_tcsanow original)
    flip finally restore $ do
      args <- getArgs
      if args == ["--serve"] then do
        hSetBuffering stdout LineBuffering
        getTerminalName slave >>= print
        let loop = do
              done <- isEOF
              unless done $ do
                command <- read <$> getLine :: IO [Int]
                case command of
                  [] -> restore >> print ([] :: [Int])
                  [action, echo] -> observe terminal original terminal (fromIntegral action) (echo /= 0) >>= print
                  _ -> fail "invalid private PTY command"
                loop
        loop
      else bracket createPipe (\(readEnd, writeEnd) -> closeFd readEnd `finally` closeFd writeEnd) $ \(Fd pipe, _) -> do
        -- Linux's three tcsetattr actions, invalid negative and maximal CInt.
        -- The fixture provider admits only its checked Linux x86_64 target.
        rows <- forM [(kind, fd, action, echo) | (kind, fd) <- [(0 :: Int,terminal),(1,pipe),(2,-1)],
                       action <- [fromIntegral P.const_tcsanow :: Int,1,2] ++ [bad | kind == 0, bad <- [-1,2147483647]], echo <- [0 :: Int,1]] $ \(kind,fd,action,echo) -> do
          result <- observe terminal original fd (fromIntegral action) (echo /= 0)
          pure (kind, action, echo, result)
        print (size, rows)
