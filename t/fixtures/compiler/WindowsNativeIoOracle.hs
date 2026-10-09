-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
-- |
-- Module      : Main
-- Copyright   : (c) Edward Kmett 2026
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : CApiFFI, ForeignFunctionInterface, native Windows Win64
--
-- Native GHC ABI evidence for the selected ghc-internal archive body, linked
-- by the named Windows native fixture producer. This probe
-- does not export Core or qualify a THC guest, console or compiled call.
{-# LANGUAGE CApiFFI #-}
{-# LANGUAGE ForeignFunctionInterface #-}
{-# LANGUAGE Unsafe #-}
module Main (main) where

import Control.Exception (bracket, finally)
import Control.Monad (unless)
import qualified Data.ByteString as BS
import Data.Bits ((.&.))
import Data.Word (Word8, Word32, Word64)
import Foreign (Ptr, FunPtr, nullPtr, nullFunPtr, sizeOf, alloca, allocaBytesAligned, allocaArray, peek, peekArray, peekByteOff)
import Foreign.C
import System.Environment (getArgs)

-- Win64 has one native calling convention; GHC rejects stdcall here. The
-- pointer/int/wchar sizes below exclude the distinct Win32 convention.
foreign import ccall unsafe "LoadLibraryW" loadLibrary :: CWString -> IO (Ptr ())
foreign import ccall unsafe "FreeLibrary" freeLibrary :: Ptr () -> IO CInt
foreign import ccall unsafe "GetProcAddress" getAddress :: Ptr () -> CString -> IO (FunPtr a)
foreign import capi unsafe "io.h _close" closeFd :: CInt -> IO CInt
foreign import capi unsafe "io.h _dup" dupFd :: CInt -> IO CInt
foreign import capi unsafe "io.h _dup2" dupTo :: CInt -> CInt -> IO CInt
foreign import capi unsafe "io.h _lseek" seekFd :: CInt -> CLong -> CInt -> IO CLong
foreign import capi unsafe "errno.h value ENOSYS" unsupportedCrt :: CInt
foreign import capi unsafe "winsock2.h value WSAEOPNOTSUPP" unsupportedSocket :: CInt

type Bind = CWString -> Ptr Word32 -> IO (Ptr ())
type Acquire = Ptr () -> CInt -> CInt -> Ptr () -> IO CInt
type Transfer = Ptr () -> Ptr () -> CInt -> CUInt -> Ptr Word8 -> Ptr () -> IO ()
type Release = Ptr () -> Ptr () -> IO ()
type Unbind = Ptr () -> IO ()
type Open = CWString -> CInt -> CUShort -> IO CInt
foreign import ccall unsafe "dynamic" bindCall :: FunPtr Bind -> Bind
foreign import ccall unsafe "dynamic" acquireCall :: FunPtr Acquire -> Acquire
foreign import ccall safe "dynamic" transferCall :: FunPtr Transfer -> Transfer
foreign import ccall unsafe "dynamic" releaseCall :: FunPtr Release -> Release
foreign import ccall unsafe "dynamic" unbindCall :: FunPtr Unbind -> Unbind
foreign import ccall unsafe "dynamic" openCall :: FunPtr Open -> Open
foreign import ccall unsafe "dynamic" abiCall :: FunPtr (IO Word64) -> IO Word64

require :: Bool -> String -> IO ()
require condition message = unless condition (ioError (userError message))

withLibrary :: FilePath -> (Ptr () -> IO a) -> IO a
withLibrary path = bracket acquire (\handle -> freeLibrary handle >>= \rc -> require (rc /= 0) "FreeLibrary failed")
  where
    acquire = withCWString path $ \name -> do
      handle <- loadLibrary name
      require (handle /= nullPtr) ("LoadLibraryW failed: " ++ path)
      pure handle

symbol :: Ptr () -> String -> IO (FunPtr a)
symbol handle name = withCString name $ \text -> do
  address <- getAddress handle text
  require (address /= nullFunPtr) ("Missing selected native export: " ++ name)
  pure address

-- | The selected package creates the descriptor. Its private native loan must
-- retain the same file and position after the original descriptor is closed.
main :: IO ()
main = do
  arguments <- getArgs
  case arguments of
    [bridgePath, packagePath, input] -> do
      require (sizeOf (nullPtr :: Ptr ()) == 8 && sizeOf (0 :: CInt) == 4 && sizeOf (0 :: CWchar) == 2) "Expected Win64 ABI"
      BS.writeFile input (BS.pack [37, 91, 122])
      withLibrary packagePath $ \package -> withLibrary bridgePath $ \bridge -> do
        abi <- symbol bridge "thc_windows_io_abi" >>= abiCall
        require (abi == 0x0000000800100010) "Boundary ABI differs from receipt"
        bind <- bindCall <$> symbol bridge "thc_windows_io_bind"
        acquire <- acquireCall <$> symbol bridge "thc_windows_io_acquire"
        transfer <- transferCall <$> symbol bridge "thc_windows_io_transfer"
        release <- releaseCall <$> symbol bridge "thc_windows_io_release"
        unbind <- unbindCall <$> symbol bridge "thc_windows_io_unbind"
        -- Errno ownership alone grants no descriptor or socket namespace.
        -- Native headers independently supply the JVM control's constants.
        require (unsupportedCrt == 40 && unsupportedSocket == 10045) "Unsupported-operation ABI differs"
        unrelated <- alloca $ \errorSlot -> withCWString "kernelbase.dll" $ \path -> do
          result <- bind path errorSlot
          errorCode <- peek errorSlot
          require (result /= nullPtr && errorCode == 0) "Actual OS errno import cannot bind"
          pure result
        flip finally (unbind unrelated) $ allocaBytesAligned 16 8 $ \loan -> do
          fileError <- acquire unrelated 1 0 loan
          require (fileError == unsupportedCrt) "Unrelated owner acquired ambient CRT descriptor"
          socketError <- acquire unrelated 1 1 loan
          require (socketError == unsupportedSocket) "Unrelated owner acquired ambient WinSock descriptor"
        -- HsBase's inline wrapper is exported by the native fixture without
        -- replacing it. Its actual mode_t is Word16; fs.c remains archive code.
        open <- openCall <$> symbol package "fixture_original_open"
        owner <- alloca $ \errorSlot -> withCWString packagePath $ \path -> do
          result <- bind path errorSlot
          errorCode <- peek errorSlot
          require (result /= nullPtr && errorCode == 0) ("Actual selected package binding failed: " ++ show errorCode)
          pure result
        flip finally (unbind owner) $ do
          -- Pinned SDK fcntl.h: O_RDWR. HsBase owns no-inherit/share flags.
          fd <- withCWString input $ \path -> open path 2 0
          require (fd >= 0) "Selected __hscore_open failed"
          allocaBytesAligned 16 8 $ \loan -> allocaBytesAligned 16 4 $ \result -> allocaArray 3 $ \bytes -> do
            errorCode <- acquire owner fd 0 loan
            require (errorCode == 0) ("Selected CRT acquisition failed: " ++ show errorCode)
            flip finally (release owner loan) $ do
              let observe count buffer = do
                    transfer owner loan 0 count buffer result
                    lengthRead <- peekByteOff result 0 :: IO CInt
                    errno <- peekByteOff result 4 :: IO CInt
                    aborted <- peekByteOff result 12 :: IO CInt
                    pure (lengthRead, errno, aborted)
              zero <- observe 0 nullPtr
              require (zero == (0, 0, 0)) ("Zero read: " ++ show zero)
              seek <- seekFd fd 0 0
              require (seek == 0) "Native GHC CRT seek failed"
              closeFd fd >>= \rc -> require (rc == 0) "Native GHC CRT close failed"
              first <- observe 2 bytes
              require (first == (2, 0, 0)) ("Partial read: " ++ show first)
              prefix <- peekArray 2 bytes
              require (prefix == [37, 91]) ("Wrong selected-file prefix: " ++ show prefix)
              second <- observe 1 bytes
              require (second == (1, 0, 0)) ("Final byte read: " ++ show second)
              suffix <- peek bytes
              require (suffix == 122) "Wrong selected-file suffix"
              eof <- observe 1 bytes
              require (eof == (0, 0, 0)) ("EOF: " ++ show eof)
              print (zero, first, prefix, second, suffix, eof)
          allocaBytesAligned 16 8 $ \loan -> do
            closed <- acquire owner fd 0 loan
            require (closed == 9) ("Closed selected descriptor did not return EBADF: " ++ show closed)
            print ("closed-errno", closed)
          -- This process alone retires fd0. The already-bound endpoint loan
          -- pins stdin's kernel object, excluding fd/kernel-handle reuse.
          savedInput <- dupFd 0
          require (savedInput >= 3) "Native oracle needs an inherited stdin descriptor"
          flip finally (do
            dupTo savedInput 0 >>= \rc -> require (rc == 0) "Restore stdin failed"
            closeFd savedInput >>= \rc -> require (rc == 0) "Retire stdin backup failed") $ do
            allocaBytesAligned 16 8 $ \loan -> do
              acquire owner savedInput 0 loan >>= \rc -> require (rc == 0) "Acquire stdin alias failed"
              flags <- peekByteOff loan 12 :: IO CInt
              release owner loan
              require (flags .&. 1 /= 0) "Duplicated stdin lost its endpoint identity"
            closeFd 0 >>= \rc -> require (rc == 0) "Retire original stdin descriptor failed"
            fileZero <- withCWString input $ \path -> open path 2 0
            require (fileZero == 0) ("Expected file fd0, got " ++ show fileZero)
            flip finally (closeFd fileZero >>= \rc -> require (rc == 0) "Retire file fd0 failed") $
              allocaBytesAligned 16 8 $ \loan -> allocaBytesAligned 16 4 $ \result -> allocaArray 3 $ \bytes -> do
                acquire owner fileZero 0 loan >>= \rc -> require (rc == 0) "Acquire file fd0 failed"
                flip finally (release owner loan) $ do
                  flags <- peekByteOff loan 12 :: IO CInt
                  require (flags == 0) ("File fd0 was mistaken for a standard endpoint: " ++ show flags)
                  transfer owner loan 0 3 bytes result
                  count <- peekByteOff result 0 :: IO CInt
                  contents <- peekArray 3 bytes
                  require (count == 3 && contents == [37, 91, 122]) "File fd0 read failed"
                  print ("file-fd0", fileZero, flags, count, contents)
      putStrLn "selected-ghc-internal-native-boundary=passed"
    _ -> ioError (userError "Usage: oracle BRIDGE_DLL SELECTED_PACKAGE_DLL INPUT")
