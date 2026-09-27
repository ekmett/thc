-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE ScopedTypeVariables, Unsafe #-}
module WindowsBridgeAudit where

import Control.Exception (catch, displayException, fromException, throwIO, toException)
import Data.List (intercalate)
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

main :: IO ()
main = mapM_ (putStrLn . intercalate "\t" . map show) $
  [[value,roundTrip value,dictionaryRoundTrip value,inertDisplay value,caughtRoundTrip value] |
    value <- [-17,0,42]]
