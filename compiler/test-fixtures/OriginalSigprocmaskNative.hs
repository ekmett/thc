-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE CApiFFI #-}
module Main where

import Control.Concurrent (runInBoundThread)
import Control.Exception (finally)
import Control.Monad (forM, unless)
import Data.Word (Word8)
import Foreign.C.Error (Errno(..), getErrno, resetErrno)
import Foreign.C.Types (CInt(..))
import Foreign.Marshal.Alloc (allocaBytes)
import Foreign.Marshal.Utils (copyBytes, fillBytes)
import Foreign.Ptr (Ptr, nullPtr, castPtr, plusPtr)
import Foreign.Storable (peekByteOff)
import qualified GHC.Internal.System.Posix.Internals as P

foreign import capi unsafe "signal.h sigdelset" deleteSignal :: Ptr P.CSigset -> CInt -> IO CInt
foreign import capi unsafe "signal.h sigismember" member :: Ptr P.CSigset -> CInt -> IO CInt
foreign import capi unsafe "signal.h value SIG_UNBLOCK" unblock :: CInt
foreign import capi unsafe "signal.h value SIG_BLOCK" block :: CInt
foreign import capi unsafe "signal.h value SIG_SETMASK" setmask :: CInt
foreign import capi unsafe "signal.h value SIGTTOU" sigttou :: CInt

checked :: IO CInt -> IO ()
checked action = action >>= \result -> unless (result == 0) (fail "Native signal-mask control failed")

main :: IO ()
main = runInBoundThread $ do
  let size = P.sizeof_sigset_t
      image action = allocaBytes size (action . castPtr)
      bytes pointer = forM [0..size-1] (\i -> fromIntegral <$> (peekByteOff pointer i :: IO Word8)) :: IO [Int]
  image $ \baseline -> image $ \normal -> image $ \token -> image $ \current -> do
    fillBytes baseline 0 size
    checked (P.c_sigprocmask block nullPtr baseline)
    (do
      copyBytes normal baseline size
      checked (deleteSignal normal sigttou)
      checked (P.c_sigprocmask setmask normal nullPtr)
      fillBytes token 0 size
      checked (P.c_sigemptyset token)
      checked (P.c_sigaddset token sigttou)
      image $ \changed -> do
        copyBytes changed normal size
        checked (P.c_sigaddset changed sigttou)
        rows <- forM [(0, -1, nullPtr, True), (1, -1, nullPtr, False),
                      (2, block, token, True), (3, block, token, True),
                      (4, unblock, token, True), (5, setmask, changed, True),
                      (6, -1, token, True), (7, setmask, normal, True)] $ \(tag, how, set, output) ->
          allocaBytes (size + 16) $ \storage -> do
            fillBytes current 0 size
            checked (P.c_sigprocmask block nullPtr current)
            before <- member current sigttou
            fillBytes storage 77 (size + 16)
            let old = castPtr (storage `plusPtr` 8)
            fillBytes old 165 size
            resetErrno
            result <- P.c_sigprocmask how set (if output then old else nullPtr)
            Errno errno <- getErrno
            oldBytes <- bytes old
            currentBytes <- bytes current
            -- Exact selected Linux x86_64 kernel mask is 8 bytes; glibc leaves
            -- the remainder of caller-owned sigset_t untouched.
            let expected = if result == 0 && output then take 8 currentBytes ++ replicate (size - 8) 165
                           else replicate size 165
            checked (P.c_sigprocmask block nullPtr current)
            after <- member current sigttou
            checked (deleteSignal current sigttou)
            others <- (==) <$> bytes current <*> bytes normal
            canaries <- forM ([0..7] ++ [size+8..size+15]) (\i -> peekByteOff storage i :: IO Word8)
            pure (tag :: Int, fromIntegral result :: Int, fromIntegral errno :: Int,
              fromIntegral before :: Int, fromIntegral after :: Int, others,
              all (==77) canaries, oldBytes == expected)
        print (size, rows))
      `finally` checked (P.c_sigprocmask setmask baseline nullPtr)
