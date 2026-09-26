-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE CPP #-}
module THC.Driver.Lock (withLock) where

#if defined(mingw32_HOST_OS)
import GHC.IO.Handle.Lock (LockMode(ExclusiveLock), hLock)
import System.IO (IOMode(ReadWriteMode), withFile)
#else
import Control.Exception (bracket)
import System.IO (SeekMode(AbsoluteSeek))
import qualified System.Posix.IO as Posix
#endif

-- The OS owns the lock until the bracket closes its handle, including on
-- exceptions or process exit. Preserve the existing POSIX record-lock protocol;
-- GHC's Windows handle lock uses the native Windows locking implementation.
withLock :: FilePath -> IO a -> IO a
#if defined(mingw32_HOST_OS)
withLock path action = withFile path ReadWriteMode $ \handle -> do
  hLock handle ExclusiveLock
  action
#else
withLock path action = bracket (Posix.openFd path Posix.ReadWrite
  (Posix.defaultFileFlags {Posix.creat = Just 0o600})) Posix.closeFd $ \descriptor -> do
    Posix.waitToSetLock descriptor (Posix.WriteLock, AbsoluteSeek, 0, 0)
    action
#endif
