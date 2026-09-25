-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE ForeignFunctionInterface #-}
module OriginalOpenRequestNative (checkOpenRequests, main) where

import Control.Concurrent (threadDelay)
import Control.Exception (bracket, finally, mask_, try, IOException)
import Control.Monad (unless, void, replicateM_)
import Data.IORef
import qualified Data.ByteString.Char8 as BSC
import Data.List (isPrefixOf)
import Foreign.C.String (CString, withCString)
import Foreign.C.Types (CInt(..), CUInt(..))
import Foreign.Marshal.Alloc (alloca)
import Foreign.Ptr (Ptr, nullPtr)
import Foreign.Storable (peek, poke)
import System.Directory (createDirectoryIfMissing, listDirectory)
import System.Environment (getArgs)
import System.Posix.Files (createNamedPipe)
import System.Posix.Signals (getSignalMask, inSignalSet)
import System.Timeout (timeout)
import qualified GHC.Internal.System.Posix.Internals as P

foreign import ccall unsafe "thc_open_start" start :: CString -> CInt -> CUInt -> Ptr CInt -> IO (Ptr ())
foreign import ccall unsafe "thc_open_done" done :: Ptr () -> IO CInt
foreign import ccall unsafe "thc_open_cancel" cancel :: Ptr () -> IO CInt
foreign import ccall safe "thc_open_finish" finish :: Ptr () -> Ptr CInt -> IO CInt
foreign import ccall unsafe "thc_open_test_pause" pause :: CInt -> IO ()
foreign import ccall unsafe "thc_open_test_stage" stage :: IO CInt
foreign import ccall unsafe "thc_open_test_disposition" disposition :: CInt -> IO ()

assert :: Bool -> String -> IO ()
assert condition message = unless condition (fail message)

untilReady :: IO Bool -> IO ()
untilReady action = do
  result <- timeout 5000000 loop
  assert (result == Just ()) "native open boundary timed out"
  where loop = action >>= \ready -> unless ready (threadDelay 1000 >> loop)

request :: FilePath -> (Ptr () -> IORef Bool -> IO a) -> IO a
request path action = withCString path $ \name -> alloca $ \errors ->
  bracket (do pointer <- start name 0 0 errors
              errorCode <- peek errors
              assert (pointer /= nullPtr && errorCode == 0) "native request startup failed"
              consumed <- newIORef False
              pure (pointer, consumed))
    (\(pointer, consumed) -> mask_ $ do
      used <- readIORef consumed
      unless used $ do
        pause 0
        void (cancel pointer)
        void (finish pointer nullPtr))
    (uncurry action)

consume :: Ptr () -> IORef Bool -> Ptr CInt -> IO CInt
consume pointer consumed lease = mask_ $ do
  result <- finish pointer lease
  assert (result >= 0) "native worker join failed"
  writeIORef consumed True
  pure result

workers :: IO [FilePath]
workers = do
  tasks <- listDirectory "/proc/self/task"
  fmap concat $ mapM (\task -> do
    name <- try (fmap BSC.unpack (BSC.readFile ("/proc/self/task/" ++ task ++ "/comm"))) :: IO (Either IOException String)
    pure [task | name == Right "thc-open\n"]) tasks

checkOpenRequests :: FilePath -> IO ()
checkOpenRequests directory = do
  originalMask <- getSignalMask
  -- A pre-existing owner is rejected without overwriting its disposition.
  disposition 1
  withCString "/dev/null" $ \name -> alloca $ \errors -> do
    pointer <- start name 0 0 errors
    errorCode <- peek errors
    assert (pointer == nullPtr && errorCode == 16) "native open stole an existing RT signal"
  disposition 0
  request "/dev/null" $ \pointer consumed -> void (consume pointer consumed nullPtr)
  baseline <- length <$> listDirectory "/proc/self/fd"
  -- Cancel before the flag check, then after actual syscall success but before
  -- fd publication. These barriers compile only into this oracle binary.
  pause 1
  request "/dev/null" $ \pointer consumed -> do
    untilReady ((== 1) <$> stage)
    cancelError <- cancel pointer
    assert (cancelError == 0) "pre-entry cancellation failed"
    pause 0
    errorCode <- consume pointer consumed nullPtr
    assert (errorCode == 4) "pre-entry cancellation did not return EINTR"
  pause 2
  request "/dev/null" $ \pointer consumed -> alloca $ \lease -> do
    poke lease (-1)
    untilReady ((== 2) <$> stage)
    cancelError <- cancel pointer
    assert (cancelError == 0) "post-syscall cancellation failed"
    pause 0
    errorCode <- consume pointer consumed lease
    fd <- peek lease
    assert (errorCode == 0 && fd >= 0) "cancellation discarded a successfully acquired fd"
    void (P.c_close fd)
  let fifo = directory ++ "/request-fifo"
  createNamedPipe fifo 0o600
  -- Observe the real owned worker inside openat, then interrupt it. No peer is
  -- opened to release the FIFO: cancellation must actually unblock the call.
  request fifo $ \pointer consumed -> do
    untilReady $ do
      active <- workers
      states <- mapM (\task -> try (fmap BSC.unpack (BSC.readFile ("/proc/self/task/" ++ task ++ "/syscall"))) :: IO (Either IOException String)) active
      pure (any (either (const False) ("257 " `isPrefixOf`)) states)
    cancelError <- cancel pointer
    assert (cancelError == 0) "blocked FIFO cancellation failed"
    untilReady ((== 1) <$> done pointer)
    errorCode <- consume pointer consumed nullPtr
    assert (errorCode == 4) "blocked FIFO cancellation did not return EINTR"
  -- Completed success wins, and aborted successful requests close their fd.
  replicateM_ 32 $ request "/dev/null" $ \pointer consumed -> do
    untilReady ((== 1) <$> done pointer)
    void (cancel pointer)
    errorCode <- consume pointer consumed nullPtr
    assert (errorCode == 0) "completed acquisition changed after cancellation"
  untilReady (null <$> workers)
  remaining <- length <$> listDirectory "/proc/self/fd"
  assert (remaining == baseline) "native open request leaked descriptors"
  finalMask <- getSignalMask
  assert (map (`inSignalSet` originalMask) [1..64] == map (`inSignalSet` finalMask) [1..64])
    "native worker changed the calling thread signal mask"
  -- A later host disposition change is rejected, never silently overwritten.
  disposition 1
  (withCString "/dev/null" $ \name -> alloca $ \errors -> do
    pointer <- start name 0 0 errors
    errorCode <- peek errors
    assert (pointer == nullPtr && errorCode == 16) "native open overwrote a later host handler")
    `finally` disposition 0

main :: IO ()
main = do
  [directory] <- getArgs
  createDirectoryIfMissing True directory
  checkOpenRequests directory
  putStrLn "owned open: before-entry, after-syscall, blocked-FIFO, completed-abort, masks and ownership passed"
