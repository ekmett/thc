-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- |
-- Module      : Main
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC-specific primitive types and operations
--
-- The oracle uses native boxed-result exception/masking effects and an
-- independent value/layout observer. GHC's continuation RTS only promises a
-- one-word return ABI (#21868); this model never calls a wide/FP/vector
-- catch or masking continuation to derive the expected THC result.
module Main (main) where

import Control.Concurrent (myThreadId, throwTo)
import Control.Exception
import Data.Int
import Data.Word
import Data.IORef
import System.Environment (getArgs)

data ExceptionPayload = ExceptionPayload Int deriving Show
instance Exception ExceptionPayload

maskTag :: MaskingState -> Int
maskTag Unmasked = 0
maskTag MaskedUninterruptible = 1
maskTag MaskedInterruptible = 2

incrementPrefix :: IORef Int -> IO ()
incrementPrefix count = do
  completed <- readIORef count
  writeIORef count $! completed + 1

boxedControl :: Int -> Int -> IO (Int, Int, Int)
boxedControl mode n = do
  count <- newIORef 0
  value <- catch
    (mask $ \restore -> uninterruptibleMask_ $ restore $ do
      incrementPrefix count
      case mode of
        1 -> throwIO (ExceptionPayload n)
        2 -> do
          tid <- myThreadId
          throwTo tid (ExceptionPayload n)
          writeIORef count 999
          pure n
        _ -> pure (n + 7))
    (\(ExceptionPayload caught) -> do
      state <- getMaskingState
      pure (caught + maskTag state * 17))
  writes <- readIORef count
  outside <- getMaskingState
  pure (value, writes, maskTag outside)

layoutValue :: String -> Int -> Int
layoutValue name value = case name of
  "int8Result" -> fromIntegral (fromIntegral value :: Int8)
  "word8Result" -> fromIntegral (fromIntegral value :: Word8)
  "int16Result" -> fromIntegral (fromIntegral value :: Int16)
  "word16Result" -> fromIntegral (fromIntegral value :: Word16)
  "int32Result" -> fromIntegral (fromIntegral value :: Int32)
  "word32Result" -> fromIntegral (fromIntegral value :: Word32)
  "int64Result" -> fromIntegral (fromIntegral value :: Int64)
  "word64Result" -> fromIntegral (fromIntegral value :: Word64)
  "floatResult" -> truncate (fromIntegral value :: Float)
  "doubleResult" -> truncate (fromIntegral value :: Double)
  "emptyResult" -> 0
  "nestedResult" -> value + truncate (fromIntegral (value + 1) :: Float)
  "sumResult" -> if value < 0 then value else truncate (fromIntegral value :: Float)
  "vectorResult" -> 4 * fromIntegral (fromIntegral value :: Int32)
  "unliftedResult" -> value
  "unliftedPayloadResult" -> value
  _ -> error "unknown result layout"

main :: IO ()
main = do
  -- Repetition must be observable: the old idempotent write of one fails this.
  repeated <- newIORef 0
  incrementPrefix repeated
  incrementPrefix repeated
  repetitions <- readIORef repeated
  if repetitions /= 2 then error "native repeated prefix did not count twice" else pure ()
  args <- getArgs
  case args of
    [name, modeText] -> mapM_ (emit name (read modeText)) [-17, 0, 23]
    _ -> error "usage: oracle NAME MODE"
  where
    row name mode n value = name ++ "\t" ++ show (mode :: Int) ++ "\t" ++ show n ++ "\t" ++ show value
    emit name mode n = do
      (value, writes, outside) <- boxedControl mode n
      if writes /= 1 || outside /= 0 then error "native no-replay/mask control failed" else
        putStrLn (row name mode n (layoutValue name value + writes * 101 + outside * 1009))
