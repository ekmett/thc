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
-- does not export Core or qualify a THC guest, Haskell console handler or
-- compiled call. Console transport is checked in its own hidden child console.
{-# LANGUAGE CApiFFI #-}
{-# LANGUAGE ForeignFunctionInterface #-}
{-# LANGUAGE Unsafe #-}
module Main (main) where

import Control.Exception (bracket, finally)
import Control.Monad (forM_, unless)
import qualified Data.ByteString as BS
import Data.Bits ((.&.))
import Data.Word (Word8, Word32, Word64)
import Foreign (Ptr, FunPtr, nullPtr, nullFunPtr, sizeOf, alloca, allocaBytesAligned, allocaArray, peek, peekArray, peekByteOff)
import Foreign.C
import System.Environment (getArgs, getExecutablePath)

-- Win64 has one native calling convention; GHC rejects stdcall here. The
-- pointer/int/wchar sizes below exclude the distinct Win32 convention.
foreign import ccall unsafe "LoadLibraryW" loadLibrary :: CWString -> IO (Ptr ())
foreign import ccall unsafe "GetModuleHandleW" getModuleHandle :: CWString -> IO (Ptr ())
foreign import ccall unsafe "FreeLibrary" freeLibrary :: Ptr () -> IO CInt
foreign import ccall unsafe "GetProcAddress" getAddress :: Ptr () -> CString -> IO (FunPtr a)
foreign import capi unsafe "io.h _close" closeFd :: CInt -> IO CInt
foreign import capi unsafe "io.h _dup" dupFd :: CInt -> IO CInt
foreign import capi unsafe "io.h _dup2" dupTo :: CInt -> CInt -> IO CInt
foreign import capi unsafe "io.h _lseek" seekFd :: CInt -> CLong -> CInt -> IO CLong
foreign import ccall unsafe "GenerateConsoleCtrlEvent" generateConsoleEvent :: Word32 -> Word32 -> IO CInt

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
foreign import ccall unsafe "dynamic" consoleOpenCall :: FunPtr (Ptr Word32 -> IO (Ptr ())) -> Ptr Word32 -> IO (Ptr ())
foreign import ccall unsafe "dynamic" consoleInstallCall :: FunPtr (Ptr () -> CInt -> Word64 -> IO Word32) -> Ptr () -> CInt -> Word64 -> IO Word32
foreign import ccall unsafe "dynamic" consoleStopCall :: FunPtr (Ptr () -> IO Word32) -> Ptr () -> IO Word32
foreign import ccall safe "dynamic" consoleWaitCall :: FunPtr (Ptr () -> IO Word32) -> Ptr () -> IO Word32
foreign import ccall unsafe "dynamic" consoleTakeCall :: FunPtr (Ptr () -> Ptr () -> IO CInt) -> Ptr () -> Ptr () -> IO CInt
foreign import ccall unsafe "dynamic" consolePendingCall :: FunPtr (Ptr () -> Word64 -> IO Word64) -> Ptr () -> Word64 -> IO Word64
foreign import ccall safe "dynamic" consoleChildCall :: FunPtr (CWString -> CWString -> IO CInt) -> CWString -> CWString -> IO CInt

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

-- | Genuine CTRL_BREAK delivery on this child's newly created console. The
-- parent SDK launcher bounds this process only; no host/JVM handlers change.
consoleOracle :: FilePath -> IO ()
consoleOracle bridgePath = do
  withLibrary bridgePath consoleQueueOracle
  withCWString bridgePath $ \name -> do
    image <- getModuleHandle name
    require (image /= nullPtr) "Retired native callback code was unloaded"

consoleQueueOracle :: Ptr () -> IO ()
consoleQueueOracle bridge = do
  open <- consoleOpenCall <$> symbol bridge "thc_windows_io_console_open"
  install <- consoleInstallCall <$> symbol bridge "thc_windows_io_console_install"
  wait <- consoleWaitCall <$> symbol bridge "thc_windows_io_console_wait"
  takeEvent <- consoleTakeCall <$> symbol bridge "thc_windows_io_console_take"
  pending <- consolePendingCall <$> symbol bridge "thc_windows_io_console_pending"
  stop <- consoleStopCall <$> symbol bridge "thc_windows_io_console_stop"
  close <- unbindCall <$> symbol bridge "thc_windows_io_console_close"
  let acquire = alloca $ \errorSlot -> do
        owner <- open errorSlot
        errorCode <- peek errorSlot
        require (owner /= nullPtr && errorCode == 0) "Child console ownership failed"
        pure owner
      retire owner = do
        stop owner >>= \rc -> require (rc == 0) "Console handler restoration failed"
        install owner (-4) 99 >>= \rc -> require (rc == 6) "Retired console accepted an installation"
        close owner
      observe owner oldGeneration newGeneration = do
        install owner (-4) oldGeneration >>= \rc -> require (rc == 0) "Install original console generation failed"
        generateConsoleEvent 1 0 >>= \rc -> require (rc /= 0) "Scoped CTRL_BREAK failed"
        wait owner >>= \rc -> require (rc == 0) "Actual console callback did not queue"
        -- Replacement cannot change an already queued event's referent.
        install owner (-4) newGeneration >>= \rc -> require (rc == 0) "Replace console generation failed"
        pending owner oldGeneration >>= \n -> require (n == 1) "Old queued generation was lost"
        allocaBytesAligned 24 8 $ \event -> do
          takeEvent owner event >>= \rc -> require (rc == 1) "Queued console event missing"
          sequenceNumber <- peekByteOff event 0 :: IO Word64
          generation <- peekByteOff event 8 :: IO Word64
          code <- peekByteOff event 16 :: IO Word32
          require (sequenceNumber == 1 && generation == oldGeneration && code == 1) "Console event ABI/identity changed"
        pending owner oldGeneration >>= \n -> require (n == 0) "Consumed console generation remained queued"
  -- Ownership is reusable after retirement. Sample repeated successful
  -- lifecycles with distinct payload generations, including rejected opens;
  -- neither success nor failure may consume a process installation budget.
  forM_ [1 .. 64] $ \generation -> bracket acquire retire $ \owner -> do
    alloca $ \errorSlot -> do
      other <- open errorSlot
      errorCode <- peek errorSlot
      require (other == nullPtr && errorCode == 170) "Concurrent process console owner was admitted"
    observe owner (2 * generation) (2 * generation + 1)

-- | The selected package creates the descriptor. Its private native loan must
-- retain the same file and position after the original descriptor is closed.
main :: IO ()
main = do
  arguments <- getArgs
  case arguments of
    ["--console-oracle", bridgePath] -> consoleOracle bridgePath
    [bridgePath, packagePath, input, sdkPath] -> do
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
      executable <- getExecutablePath
      withLibrary sdkPath $ \sdk -> do
        launch <- consoleChildCall <$> symbol sdk "fixture_console_child"
        rc <- withCWString executable $ \exe -> withCWString bridgePath $ \bridge -> launch exe bridge
        require (rc == 0) ("Isolated native console child failed: " ++ show rc)
      putStrLn "selected-ghc-internal-native-boundary=passed; isolated-console-queue=passed"
    _ -> ioError (userError "Usage: oracle BRIDGE_DLL SELECTED_PACKAGE_DLL INPUT SDK_DLL")
