-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE OverloadedStrings #-}
module SimdByteArrayModel
  ( Family(..), Entry(..), families, familyName, moduleName, element, lanes, floating
  , entries, entryValue, graphEntries, graphNames, helper, guestCalls, rows, diagnostics
  , expected, expectedRows, encodeRows, encodeRequests, signed64
  ) where

import Data.Aeson (Value, object, (.=))
import Data.Bits ((.&.), (.|.), xor, shiftL, shiftR)
import Data.List (isPrefixOf, isSuffixOf, intercalate)

-- The four existing 128-bit memory corpora. Integer byte assembly only: no
-- Vector API, native oracle, floating bitcasts or guest implementation here.
data Family = Int32Lanes | Word32Lanes | FloatLanes | DoubleLanes deriving (Eq, Show)
data Entry = Entry { entryName :: String, entryArity :: Int, entryCases :: [[Integer]] }

families :: [Family]
families = [Int32Lanes,Word32Lanes,FloatLanes,DoubleLanes]

familyName :: Family -> String
familyName family = case family of
  Int32Lanes -> "int32x4-bytearray"
  Word32Lanes -> "word32x4-bytearray"
  FloatLanes -> "floatx4-bytearray"
  DoubleLanes -> "doublex2-bytearray"

moduleName :: Family -> String
moduleName family = "Simd" ++ case family of
  Int32Lanes -> "Int32X4ByteArray"
  Word32Lanes -> "Word32X4ByteArray"
  FloatLanes -> "FloatX4ByteArray"
  DoubleLanes -> "DoubleX2ByteArray"

element :: Family -> String
element family = case family of
  Int32Lanes -> "Int32ElemRep"
  Word32Lanes -> "Word32ElemRep"
  FloatLanes -> "FloatElemRep"
  DoubleLanes -> "DoubleElemRep"

lanes :: Family -> Int
lanes DoubleLanes = 2
lanes _ = 4

floating :: Family -> Bool
floating family = family `elem` [FloatLanes,DoubleLanes]

signed64 :: Integer -> Integer
signed64 value = (value + 2^(63 :: Int)) `mod` 2^(64 :: Int) - 2^(63 :: Int)

narrow :: Family -> Integer -> Integer
narrow Int32Lanes value = (value + 2^(31 :: Int)) `mod` 2^(32 :: Int) - 2^(31 :: Int)
narrow DoubleLanes value = signed64 value
narrow _ value = value `mod` 2^(32 :: Int)

seeds :: [Integer]
seeds = [-2^(63 :: Int),-4294967297,-2147483649,-2147483648,-2147483647,-1,0,1,127,128,255,256,
         2147483646,2147483647,2147483648,4294967295,4294967296,2^(63 :: Int)-1]

edges :: Family -> [Integer]
edges family = case family of
  Int32Lanes -> [-2147483648,-2147483647,-65537,-1,0,1,65537,2147483646,2147483647]
  Word32Lanes -> [0,1,65535,65536,2147483647,2147483648,2147483649,4294967294,4294967295]
  FloatLanes -> [-65536,-257,-1,0,1,255,256,65535]
  DoubleLanes -> [-2^(40 :: Int),-2^(32 :: Int)-1,-1,0,1,2^(32 :: Int)-1,2^(40 :: Int)-1,2^(40 :: Int)]

sentinels :: Family -> [Integer]
sentinels family = case family of
  Int32Lanes -> [0x1234567,-0x2345678,0x3456789,-0x456789a]
  Word32Lanes -> [0x81234567,0x92345678,0xa3456789,0xb456789a]
  FloatLanes -> [-31,127,-1024,4096]
  DoubleLanes -> [-31,127]

rawBits :: Family -> Bool -> [Integer]
rawBits FloatLanes False = [0,0x80000000,1,0x80000001,0x007fffff,0x807fffff,0x00800000,0x80800000,
  0x3f800000,0xbf800000,0x7f7fffff,0xff7fffff,0x7f800000,0xff800000,0x7fc12345,0xffc54321]
rawBits FloatLanes True = [0x7f800001,0xff800001,0x7fa12345,0xffa54321]
rawBits DoubleLanes False = map signed64 [0,0x8000000000000000,1,0x8000000000000001,
  0x000fffffffffffff,0x800fffffffffffff,0x0010000000000000,0x8010000000000000,0x3ff0000000000000,0xbff0000000000000,
  0x7fefffffffffffff,0xffefffffffffffff,0x7ff0000000000000,0xfff0000000000000,0x7ff8123456789abc,0xfff8abcdef012345]
rawBits DoubleLanes True = map signed64 [0x7ff0000000000001,0xfff0000000000001,0x7ff123456789abcd,0xfff543210fedcba9]
rawBits _ _ = error "Only floating memory has raw IEEE patterns"

familiesOfOffsets :: [String]
familiesOfOffsets = ["vector","scalar"]

scale :: Family -> String -> Int
scale family name
  | "vector" `isPrefixOf` name = 16
  | "scalar" `isPrefixOf` name = 16 `div` lanes family
  | otherwise = error "Unknown SIMD memory offset family"

patterns :: Family -> String -> Bool -> Bool -> [[Integer]]
patterns family offsetFamily graph diagnostic = zipWith (\i row -> fromIntegral (i `mod` count) : row) [0..] values
  where
    count = (64-16) `div` scale family offsetFamily + 1
    values | not (floating family) || graph =
               [take lane (sentinels family) ++ [boundary] ++ drop (lane+1) (sentinels family)
               | lane <- [0..lanes family-1], boundary <- edges family]
           | otherwise = let domain = rawBits family diagnostic
                         in [[domain !! ((i+5*lane) `mod` length domain) | lane <- [0..lanes family-1]] | i <- [0..length domain-1]]

entries :: Family -> [Entry]
entries family
  | floating family = [make offsetFamily operation selectors | offsetFamily <- familiesOfOffsets,
      (operation,selectors) <- [("Unit",2*lanes family),("Index",lanes family),("Read",lanes family),("Write",64),("GraphIndex",0),("GraphStore",64)]]
  | otherwise = [Entry (offsetFamily ++ "UnitCase") 2 [[offset,seed] | offset <- [0..fromIntegral ((64-16) `div` scale family offsetFamily)], seed <- seeds]
                 | offsetFamily <- familiesOfOffsets] ++
                [make offsetFamily operation selectors | offsetFamily <- familiesOfOffsets, (operation,selectors) <- [("Index",0),("Read",0),("Write",64),("Store",64)]]
  where
    make :: String -> String -> Int -> Entry
    make offsetFamily operation selectors = Entry (offsetFamily ++ operation ++ "Case")
      (1 + lanes family + if selectors == 0 then 0 else 1)
      (let domain = patterns family offsetFamily ("Graph" `isPrefixOf` operation) False
       in if selectors == 0 then domain else [args ++ [fromIntegral selector] | args <- domain, selector <- [0..selectors-1]])

entryValue :: Entry -> Value
entryValue entry = object ["name" .= entryName entry,"arity" .= entryArity entry,"cases" .= entryCases entry]

helper :: Family -> String -> Maybe String
helper family name = lookup name
  [(offsetFamily ++ operation ++ "Case", offsetFamily ++ target operation) | offsetFamily <- familiesOfOffsets,
    operation <- if floating family then ["Index","Read","Write","GraphIndex","GraphStore"] else ["Index","Read","Write","Store"]]
  where target "GraphIndex" = "IndexGraph"
        target "GraphStore" = "StoreGraph"
        target "Store" = "StoreGraph"
        target operation = operation ++ "Worker"

guestCalls :: Family -> String -> Int
guestCalls family name
  | floating family && "IndexCase" `isSuffixOf` name && not (any (`isPrefixOf` name) ["vectorGraph","scalarGraph"]) = 4
  | Just _ <- helper family name = 3
  | otherwise = 2

initialBytes :: Family -> [Integer]
initialBytes family = [((base + fromIntegral (i `div` 4)*0x01030507) `shiftR` (8*(i `mod` 4))) .&. 255 | i <- [0..63 :: Int]]
  where base = if family == Int32Lanes then 0x10203040 else 0x89abcdef

integerBits :: Family -> Integer -> Integer
integerBits _ 0 = 0
integerBits family value = sign .|. ((fromIntegral power+bias) `shiftL` fraction) .|.
  ((abs value-2^power) `shiftL` (fraction-power))
  where
    fraction = if family == DoubleLanes then 52 else 23
    bias = if family == DoubleLanes then 1023 else 127
    power = length (takeWhile (<= abs value) (iterate (*2) 1)) - 1
    sign = if value < 0 then 1 `shiftL` (if family == DoubleLanes then 63 else 31) else 0

putLanes :: Family -> [Integer] -> Int -> [Integer] -> [Integer]
putLanes family bytes address values
  | length values /= lanes family || address < 0 || address > length bytes-16 = error "Out of bounds SIMD model access"
  | otherwise = take address bytes ++ encoded ++ drop (address+16) bytes
  where width = 16 `div` lanes family
        encoded = [(value `shiftR` (8*byte)) .&. 255 | value <- values, byte <- [0..width-1]]

readLanes :: Family -> [Integer] -> Int -> [Integer]
readLanes family bytes address = [narrow family (sum [bytes !! (address+width*lane+byte) `shiftL` (8*byte) | byte <- [0..width-1]])
                               | lane <- [0..lanes family-1]]
  where width = 16 `div` lanes family

checksum :: Family -> [Integer] -> Integer
checksum family values = sum (zipWith (*) (if floating family then values else map (narrow family) values) [3,5,7,11])

expected :: Family -> String -> [Integer] -> Integer
expected family name [offset,seed] | not (floating family) && "UnitCase" `isSuffixOf` name =
  checksum family (readLanes family before address) + 15*checksum family (readLanes family after address)
  where
    address = fromInteger offset * scale family name
    before = putLanes family (replicate 64 0) address [seed,seed+17,seed*3-29,seed `xor` 0x55aa55aa]
    changed = if "vector" `isPrefixOf` name then [(seed `xor` 0x80000000) `shiftR` (8*byte) .&. 255 | byte <- [0..3]] else [(seed+101) .&. 255]
    at = address + if "vector" `isPrefixOf` name then 4 else 7
    after = take at before ++ changed ++ drop (at+length changed) before
expected family name arguments@(rawOffset:rawValues)
  | floating family && "UnitCase" `isSuffixOf` name = narrow family (bits !! (selector `mod` lanes family) `xor`
      if selector == lanes family+1 then 1 `shiftL` (if family == DoubleLanes then 63 else 31) else 0)
  | floating family && not graph && any (`isSuffixOf` name) ["IndexCase","ReadCase"] = narrow family (bits !! selector)
  | "GraphIndexCase" `isSuffixOf` name || not (floating family) && any (`isSuffixOf` name) ["IndexCase","ReadCase"] = score
  | family == DoubleLanes && not graph = signed64 (foldr xor (storage !! selector) values)
  | otherwise = score*257 + storage !! selector
  where
    offset = fromInteger rawOffset
    values = take (lanes family) rawValues
    selector = fromInteger (last arguments)
    graph = any (`isPrefixOf` name) ["vectorGraph","scalarGraph"]
    bits = if graph then map (integerBits family) values else values
    storage = putLanes family (initialBytes family) (offset*scale family name) bits
    score = checksum family values
expected _ _ [] = error "Missing SIMD model arguments"

graphNames :: Family -> [String]
graphNames family = [offsetFamily ++ operation ++ (if operation == "Index" && not (floating family) then "Worker" else "Graph")
                   | offsetFamily <- familiesOfOffsets, operation <- ["Index","Store"]]

graphEntries :: Family -> [Value]
graphEntries family = [object ["name" .= name,"arity" .= (if operation == "Index" then 2 else lanes family+3),
    "operation" .= (if operation == "Index" then "index" else "store" :: String),"offsetUnitBytes" .= scale family offsetFamily,
    "cases" .= [let before = initialBytes family
                    after = putLanes family before (fromInteger offset*scale family offsetFamily)
                      (if floating family then map (integerBits family) values else values)
                in object ["offset" .= offset,"lanes" .= values,"initialBytes" .= (if operation == "Index" then after else before),
                  "expectedBytes" .= after,"expectedScalar" .= checksum family values,
                  "nativeEntry" .= (offsetFamily ++ (if floating family then "Graph" else "") ++ operation ++ "Case"),"nativeArguments" .= args]
               | args@(offset:values) <- patterns family offsetFamily (floating family) False]]
  | (name,(offsetFamily,operation)) <- zip (graphNames family) [(f,o) | f <- familiesOfOffsets,o <- ["Index","Store"]]]

expectedRows :: Family -> Int
expectedRows FloatLanes = 6720
expectedRows DoubleLanes = 4384
expectedRows _ = 9666

rows :: Family -> [(String,[Integer],Integer)]
rows family = [(entryName entry,args,expected family (entryName entry) args) | entry <- entries family,args <- entryCases entry]

diagnostics :: Family -> [(String,[Integer],Integer)]
diagnostics family = [(name,args,expected family name args) | offsetFamily <- familiesOfOffsets,
  (operation,count) <- [("Unit",2*lanes family),("Index",lanes family),("Read",lanes family),("Write",64)],
  patternArgs <- patterns family offsetFamily False True, selector <- [0..count-1],
  let name = offsetFamily ++ operation ++ "Case", let args = patternArgs ++ [fromIntegral selector]]

encodeRows :: [(String,[Integer],Integer)] -> String
encodeRows = unlines . map (\(name,args,result) -> intercalate "\t" (name:map show (args ++ [result])))

encodeRequests :: [(String,[Integer],Integer)] -> String
encodeRequests = unlines . map (\(name,args,_) -> intercalate "\t" (name:map show args))
