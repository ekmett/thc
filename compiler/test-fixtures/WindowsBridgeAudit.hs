-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, ScopedTypeVariables, Unsafe #-}

-- |
-- Module      : WindowsBridgeAudit
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Native GHC; unsafe THC exception-dictionary fixture
--
-- Exercise the compiled foreign-exception dictionary and opaque boxer/projector
-- used by Windows acquisition. Synthetic payloads test dictionary plumbing only;
-- they are not foreign objects and must not be passed to metadata inspection.
module WindowsBridgeAudit where

import Control.Exception (catch, displayException, fromException, throwIO, toException)
import Data.List (intercalate)
import GHC.Exts (Int(I#), Int#)
import System.IO.Unsafe (unsafePerformIO)
import THC.Exception (ForeignException)
import THC.Internal.Exception (boxForeign, projectForeign)
import Unsafe.Coerce (unsafeCoerce)

-- The runtime library supplies the real opaque helpers, existential dictionary
-- and Typeable projection. The fixture never constructs SomeException itself.
roundTrip :: Int -> Int
roundTrip value = unsafeCoerce (projectForeign (boxForeign (unsafeCoerce value)))
{-# OPAQUE roundTrip #-}

dictionaryRoundTrip :: Int -> Int
dictionaryRoundTrip value = case fromException (boxForeign (unsafeCoerce value)) of
  Just (exception :: ForeignException) -> unsafeCoerce (projectForeign (toException exception))
  Nothing -> -999
{-# OPAQUE dictionaryRoundTrip #-}

inertDisplay :: Int -> Int
inertDisplay value = value + length (displayException (boxForeign (unsafeCoerce value)))
{-# OPAQUE inertDisplay #-}

caughtRoundTrip :: Int -> Int
caughtRoundTrip value = unsafePerformIO $
  throwIO (boxForeign (unsafeCoerce value)) `catch` \(exception :: ForeignException) ->
    pure (unsafeCoerce (projectForeign (toException exception)))
{-# OPAQUE caughtRoundTrip #-}

-- The scalar launcher supplies Int# carriers. Keep its ABI separate from the
-- original boxed helpers and let GHC compile the actual boxing/unboxing.
roundTripScalar, dictionaryRoundTripScalar, inertDisplayScalar, caughtRoundTripScalar :: Int# -> Int#
roundTripScalar value = case roundTrip (I# value) of I# result -> result
dictionaryRoundTripScalar value = case dictionaryRoundTrip (I# value) of I# result -> result
inertDisplayScalar value = case inertDisplay (I# value) of I# result -> result
caughtRoundTripScalar value = case caughtRoundTrip (I# value) of I# result -> result

main :: IO ()
main = mapM_ (putStrLn . intercalate "\t" . map show) $
  [[value,I# (roundTripScalar raw),I# (dictionaryRoundTripScalar raw),
    I# (inertDisplayScalar raw),I# (caughtRoundTripScalar raw)] |
    value@(I# raw) <- [-17,0,42]]
