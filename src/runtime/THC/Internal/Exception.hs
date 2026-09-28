-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE ForeignFunctionInterface, Unsafe #-}

-- |
-- Module      : THC.Internal.Exception
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC FFI; THC runtime services or native fallback implementation
--
-- Private foreign-exception controls. Arbitrary pointers and foreign object construction
-- are unsafe; use the abstract, typed @THC.Exception@ interface instead.
module THC.Internal.Exception (ForeignException(..), boxForeign, projectForeign, exceptionText) where

import Control.Exception (Exception, SomeException, bracket, fromException, toException)
import Data.Char (chr)
import Foreign.C.Types (CInt(..), CLLong(..))
import Foreign.StablePtr (StablePtr, freeStablePtr, newStablePtr)
import GHC.Exts (Any)
import Unsafe.Coerce (unsafeCoerce)

-- | The runtime owns the opaque foreign object; this newtype gives it a genuine
-- compiled Haskell Exception dictionary without exposing its representation.
newtype ForeignException = ForeignException Any

instance Show ForeignException where
  showsPrec _ _ = showString "Foreign exception"
instance Exception ForeignException

foreign import ccall safe "thc_exception_v1_text"
  queryText :: StablePtr Any -> CInt -> CLLong -> IO CLLong

-- | Wrap a runtime-owned foreign object with its compiled exception dictionary.
-- Supplying an arbitrary Haskell value does not make it a valid foreign object.
-- These helpers are compiled by GHC, including the actual existential dictionary
-- and Typeable projection. Neither the runtime nor its tests forge SomeException.
boxForeign :: Any -> SomeException
boxForeign raw = toException (ForeignException raw)
{-# OPAQUE boxForeign #-}

-- | Recover the opaque object from a foreign exception, or return the internal
-- unit-shaped sentinel for another exception. Only the runtime boundary may
-- interpret this untyped result.
projectForeign :: SomeException -> Any
projectForeign value = case fromException value of
  Just (ForeignException raw) -> raw
  Nothing -> unsafeCoerce ()
{-# OPAQUE projectForeign #-}

-- | Metadata inspection is effectful and may itself fail in the foreign language.
-- The runtime publishes an immutable snapshot before indexing its codepoints.
-- Concurrent first queries may inspect independently; the first published value wins.
exceptionText :: Int -> ForeignException -> IO (Maybe String)
exceptionText selector (ForeignException raw) =
  bracket (newStablePtr raw) freeStablePtr $ \handle -> do
    size <- queryText handle (fromIntegral selector) (-1)
    if size < 0 then pure Nothing else Just <$> characters handle 0 size
  where
    characters handle index count
      | index == count = pure []
      | otherwise = do
          value <- queryText handle (fromIntegral selector) index
          rest <- characters handle (index + 1) count
          pure (chr (fromIntegral value) : rest)
