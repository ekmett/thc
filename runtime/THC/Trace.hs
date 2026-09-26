-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE ScopedTypeVariables, Trustworthy #-}

-- | Context-local structured diagnostics. Selecting JFR never starts a JVM
-- recording; an enabled sink is not evidence that a recording is consuming it.
module THC.Trace
  ( Availability(..), TraceSink(..), getTraceSink, supportedTraceSinks
  , setTraceSink, traceEvent, withSpan
  ) where

import Control.Exception (SomeAsyncException, SomeException, catch, fromException, mask, throwIO, try)
import Data.Bits ((.&.), (.|.), shiftR)
import Data.Char (ord)
import Data.Int (Int64)
import Data.Word (Word8)
import Foreign.Marshal.Array (withArrayLen)
import Foreign.Ptr (nullPtr)
import THC.Internal.RuntimeABI

data TraceSink = TraceOff | TraceStderr | TraceJFR | TraceStderrAndJFR
  deriving (Eq, Ord, Show)

getTraceSink :: IO (Availability TraceSink)
getTraceSink = queryEnum 500 [(0, TraceOff), (1, TraceStderr), (2, TraceJFR), (3, TraceStderrAndJFR)]

supportedTraceSinks :: IO (Availability [TraceSink])
supportedTraceSinks = queryEnum 501
  [(0, [TraceOff]), (1, [TraceOff, TraceStderr]), (2, [TraceOff, TraceJFR]),
   (3, [TraceOff, TraceStderr, TraceJFR, TraceStderrAndJFR])]

setTraceSink :: TraceSink -> IO (Availability ())
setTraceSink sink = control 500 $ case sink of
  TraceOff -> 0
  TraceStderr -> 1
  TraceJFR -> 2
  TraceStderrAndJFR -> 3

-- | Emit a UTF-8 label, preserving embedded NUL. Limited to 1 MiB encoded;
-- invalid Unicode surrogate characters become U+FFFD. Data is never evaluated
-- as code. 'Disabled' emits nothing; native GHC reports 'Unsupported'.
traceEvent :: String -> IO (Availability ())
traceEvent label = fmap (fmap (const ())) (emitLabel 0 label)

-- | Bracket the IO action with a context-owned span when tracing is enabled.
-- Begin/end run masked; the action runs with the caller's original mask.
-- Unsupported/disabled tracing still executes the action. Cleanup never
-- replaces the action's exception, and synchronous cleanup failures do not
-- replace a successful result. Asynchronous exceptions on successful cleanup
-- still propagate. The span covers the IO action, not later evaluation of its
-- returned lazy value. A sink change during the action applies to its end.
withSpan :: String -> IO a -> IO a
withSpan label action = mask $ \restore -> do
  started <- emitLabel 1 label
  outcome <- try (restore action)
  case outcome of
    Left (original :: SomeException) -> do
      _ <- try (finish 3 started) :: IO (Either SomeException ())
      throwIO original
    Right value -> do
      finish 2 started `catch` \(failure :: SomeException) ->
        case fromException failure :: Maybe SomeAsyncException of
          Just _ -> throwIO failure
          Nothing -> pure ()
      pure value
  where
    finish operation (Available token) = do
      _ <- traceCall operation token nullPtr 0
      pure ()
    finish _ _ = pure ()

emitLabel :: Int -> String -> IO (Availability Int64)
emitLabel operation label = do
  -- Bound traversal/allocation even for an infinite input string.
  let bytes = take (1048576 + 1) (concatMap encode label)
  if length bytes > 1048576
    then fail "THC trace label exceeds 1 MiB UTF-8 limit"
    else withArrayLen bytes (\count pointer -> traceCall operation 0 pointer count)
  where
    encode :: Char -> [Word8]
    encode character
      | point < 0x80 = [byte point]
      | point < 0x800 = [byte (0xc0 .|. shiftR point 6), continuation point]
      | point < 0x10000 = [byte (0xe0 .|. shiftR point 12), continuation (shiftR point 6), continuation point]
      | otherwise = [byte (0xf0 .|. shiftR point 18), continuation (shiftR point 12),
                     continuation (shiftR point 6), continuation point]
      where
        original = ord character
        point | original >= 0xd800 && original <= 0xdfff = 0xfffd
              | otherwise = original
    byte = fromIntegral
    continuation point = byte (0x80 .|. (point .&. 0x3f))
