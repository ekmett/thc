-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE ForeignFunctionInterface, MagicHash #-}
module Main where

import Control.Exception (evaluate)
import Control.Monad (forM)
import Data.Word (Word8)
import Foreign
import Foreign.C
import GHC.Exts (Int(I#), Ptr(Ptr))
import OriginalIconvAudit

data Case = Case String String String [Word8] Int
cases :: [Case]
cases = [Case "utf8-utf16" "UTF-16LE" "UTF-8" [65,0xc3,0xa9,0xf0,0x9f,0x98,0x80] 16,
  Case "latin1-utf8" "UTF-8" "ISO-8859-1" [0x41,0xe9,0xff] 16,
  Case "locale-default" "" "UTF-8" [0xc3,0xa9] 16,
  Case "transliteration" "ASCII//TRANSLIT" "UTF-8" [0xc3,0xa9,0xc3,0x9f] 16,
  Case "output-full" "UTF-16LE" "UTF-8" [65,66] 2,
  Case "zero-output" "UTF-16LE" "UTF-8" [65] 0,
  Case "incomplete" "UTF-16LE" "UTF-8" [65,0xc3] 16,
  Case "invalid" "UTF-16LE" "UTF-8" [65,0xff] 16,
  Case "empty" "UTF-8" "UTF-8" [] 0,
  Case "stateful" "ISO-2022-JP" "UTF-8" [0xe3,0x81,0x82] 16]

open :: String -> String -> IO Int
open to from = withCString to $ \(Ptr to#) -> withCString from $ \(Ptr from#) ->
  evaluate (I# (originalIconvOpen to# from#))
close :: Int -> IO Int
close (I# handle) = evaluate (I# (originalIconvClose handle))

row :: Int -> Bool -> [Word8] -> Int -> IO (Int, Int, Int, Int, [Word8])
row (I# handle) reset input capacity = withArray input $ \inputStart -> allocaBytes (max 1 capacity) $ \outputStart ->
  alloca $ \inputCell -> alloca $ \outputCell -> alloca $ \inputCount -> alloca $ \outputCount -> do
    poke inputCell inputStart; poke outputCell outputStart
    poke inputCount (fromIntegral (length input) :: CSize); poke outputCount (fromIntegral capacity :: CSize)
    fillBytes outputStart 0xa5 (max 1 capacity)
    resetErrno
    let Ptr input# = if reset then nullPtr else inputCell
        Ptr output# = outputCell
        Ptr inputCount# = inputCount
        Ptr outputCount# = outputCount
    result <- evaluate (I# (originalIconv handle input# inputCount# output# outputCount#))
    Errno err <- getErrno
    left <- fromIntegral <$> peek inputCount
    remaining <- fromIntegral <$> peek outputCount
    updatedInput <- peek inputCell; updatedOutput <- peek outputCell
    let consumed = if reset then 0 else length input - left
        produced = capacity - remaining
    if updatedInput `minusPtr` inputStart /= consumed || updatedOutput `minusPtr` outputStart /= produced
      then error "Native iconv cursor/count mismatch" else pure ()
    bytes <- peekArray capacity outputStart
    pure (result, fromIntegral err, consumed, produced, bytes)

main :: IO ()
main = do
  let name = Ptr (originalLocale 0#) :: CString
  locale <- peekCString name
  rows <- forM cases $ \(Case label to from input capacity) -> do
    handle <- open to from
    if handle == -1 then error "Native test encoding unavailable" else pure ()
    first <- row handle False input capacity
    let (_, _, consumed, _, _) = first
        continuationInput = if label == "stateful" then input else drop consumed input
    continued <- row handle False continuationInput 16
    smallReset <- row handle True [] 2
    flushed <- row handle True [] 16
    closed <- close handle
    pure (label, to, from, map fromIntegral input :: [Int], capacity, first,
      map fromIntegral continuationInput :: [Int], continued, smallReset, flushed, closed)
  resetErrno
  missing <- open "THC-invalid-encoding" "UTF-8"
  Errno missingError <- getErrno
  print (locale, missing, fromIntegral missingError :: Int, rows)
