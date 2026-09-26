-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples #-}
module Main where

import Control.Exception (evaluate)
import Control.Monad (forM, forM_)
import GHC.Exts
import GHC.IO (IO(..))
import OriginalGmpAudit ()
import qualified GHC.Internal.Bignum.Backend.GMP as G
import qualified GHC.Internal.Bignum.Primitives as P

data Buffer = Buffer (MutableByteArray# RealWorld)
data Frozen = Frozen ByteArray#
data Request = Request String String [Word] [Word] Word Int

canaries :: [Word]
canaries = [0x13579bdf2468ace0, 0xfedcba9876543210]

buffer :: [Word] -> IO Buffer
buffer values = do
  result <- case length values * 8 of
    I# bytes -> IO $ \state ->
      case newByteArray# bytes state of (# next, array #) -> (# next, Buffer array #)
  forM_ (zip [0..] values) $ \(I# index, W# word) -> case result of
    Buffer array -> IO $ \state -> case writeWordArray# array index word state of next -> (# next, () #)
  pure result

freeze :: Buffer -> IO Frozen
freeze (Buffer array) = IO $ \state -> case unsafeFreezeByteArray# array state of
  (# next, frozen #) -> (# next, Frozen frozen #)

observe :: Buffer -> IO [Int]
observe (Buffer array) = do
  count <- IO $ \state -> case getSizeofMutableByteArray# array state of (# next, bytes #) -> (# next, I# bytes `div` 8 #)
  forM [0..count-1] $ \(I# index) -> IO $ \state ->
    case readWordArray# array index state of (# next, word #) -> (# next, I# (word2Int# word) #)

-- All native calls obey GMP's domains. Invalid capacities, divisors and overlap
-- are managed-runtime rejection controls, never unsafe native experiments.
requests :: [Request]
requests = concat
  [ [Request entry alias a b 0 0
    | entry <- ["originalAdd", "originalSub"], alias <- ["none", "output-left", "output-right"],
      (a,b) <- [([0],[1]), ([maxBound],[1]), ([0,0],[1]), ([maxBound,maxBound],[1,1]), ([7,2,1],[3,1])]]
  , [Request entry alias a [] word 0
    | entry <- ["originalAddWord", "originalMulWord"], alias <- ["none", "output-left"],
      (a,word) <- [([0],0),([maxBound],1),([maxBound,maxBound],maxBound),([7,2,1],19)]]
  , [Request "originalCmp" "none" a b 0 0
    | (a,b) <- [([0],[0]),([0],[1]),([maxBound],[1]),([9,1],[0,2]),([3,2],[3,2])]]
  , [Request "originalMul" "none" a b 0 0
    | (a,b) <- [([0],[0]),([maxBound],[maxBound]),([7,2],[3]),([maxBound,maxBound],[maxBound,maxBound])]]
  , [Request "originalDivWord" alias a [] divisor fractional
    | alias <- ["none", "output-left"],
      (a,divisor,fractional) <- [([],3,0),([],3,2),([7],3,0),([7,2],3,0),([maxBound,maxBound],maxBound,0),([7],3,2)]]
  , [Request "originalModWord" "none" a [] divisor 0
    | (a,divisor) <- [([],1),([],maxBound),([7],3),([7,2],3),([maxBound,maxBound],maxBound)]]
  , [Request entry alias a b 0 0
    | entry <- ["originalQuotRem", "originalQuot", "originalRem"],
      alias <- if entry == "originalQuot" then ["none"] else ["none", "remainder-left"],
      (a,b) <- [([7],[3]),([7,2],[3,1]),([1,0,1],[maxBound,1]),([maxBound,maxBound,maxBound],[maxBound,1])]]
  , [Request entry alias a [] shift 0
    | entry <- ["originalRShift", "originalRShiftNegative"],
      alias <- if entry == "originalRShift" then ["none", "output-left"] else ["none"],
      a <- [[1],[maxBound],[1,1],[0,maxBound],[maxBound,maxBound],[1,0,1]],
      shift <- [1,63,64,65,127], shift < fromIntegral (length a * 64) ]
  , [Request "originalGetDouble" "none" a [] (fromIntegral (sign * length a)) exponent
    | a <- [[],[0],[1],[maxBound],[1,1],[maxBound,maxBound],[1,0,1]],
      sign <- [-1,1], exponent <- [-1075,-1023,-1,0,1,1024] ]
  , [Request "originalEncodeDouble" "none" [] [] (fromIntegral mantissa) exponent
    | mantissa <- [0,1,-1,minBound,maxBound,9007199254740993] :: [Int],
      exponent <- [minBound,-1075,-1074,-1,0,1,1024,maxBound] ]
  ]

main :: IO ()
main = mapM execute requests >>= print
  where
    execute (Request entry alias left right word fractional) = do
      let nl = length left
          nr = length right
          outputCount = case entry of
            "originalCmp" -> 0
            "originalModWord" -> 0
            "originalGetDouble" -> 0
            "originalEncodeDouble" -> 0
            "originalRShift" -> nl - fromIntegral word `div` 64
            "originalRShiftNegative" -> nl - (fromIntegral word - 1) `div` 64
            "originalMul" -> nl + nr
            "originalDivWord" -> nl + fractional
            "originalQuotRem" -> nl - nr + 1
            "originalQuot" -> nl - nr + 1
            "originalRem" -> nr
            _ -> nl
          remainderCount = if entry == "originalQuotRem" then nr else 0
          pad count xs = xs ++ replicate (max 0 (count-length xs)) 0x1122334455667788 ++ canaries
          leftCapacity = max nl (if alias == "output-left" then outputCount else if alias == "remainder-left" then nr else 0)
          rightCapacity = max nr (if alias == "output-right" then outputCount else 0)
      leftBuffer <- buffer (pad leftCapacity left)
      rightBuffer <- buffer (pad rightCapacity right)
      output <- case alias of
        "output-left" -> pure leftBuffer
        "output-right" -> pure rightBuffer
        "remainder-left" | entry == "originalRem" -> pure leftBuffer
        _ -> buffer (pad outputCount [])
      remainder <- if alias == "remainder-left" && entry == "originalQuotRem" then pure leftBuffer
        else buffer (pad remainderCount [])
      beforeLeft <- observe leftBuffer
      beforeRight <- observe rightBuffer
      beforeOutput <- observe output
      beforeRemainder <- observe remainder
      Frozen a <- freeze leftBuffer
      Frozen b <- freeze rightBuffer
      result <- case (output,remainder,nl,nr,word,fractional) of
        (Buffer out,Buffer rem,I# an,I# bn,W# w,I# fraction) ->
          let withWord action = fromIntegral <$> action
              withVoid action = action >> pure 0
          in case entry of
            "originalAdd" -> withWord (G.c_mpn_add out a an b bn)
            "originalSub" -> withWord (G.c_mpn_sub out a an b bn)
            "originalMul" -> withWord (G.c_mpn_mul out a an b bn)
            "originalAddWord" -> withWord (G.c_mpn_add_1 out a an w)
            "originalMulWord" -> withWord (G.c_mpn_mul_1 out a an w)
            "originalDivWord" -> withWord (G.c_mpn_divrem_1 out fraction a an w)
            "originalCmp" -> evaluate (I# (G.c_mpn_cmp a b an))
            "originalModWord" -> evaluate (I# (word2Int# (G.c_mpn_mod_1 a an w)))
            "originalQuotRem" -> withVoid (G.c_mpn_tdiv_qr out rem fraction a an b bn)
            "originalQuot" -> withVoid (G.c_mpn_tdiv_q out a an b bn)
            "originalRem" -> withVoid (G.c_mpn_tdiv_r out a an b bn)
            "originalRShift" -> withWord (G.c_mpn_rshift out a an w)
            "originalRShiftNegative" -> withWord (G.c_mpn_rshift_2c out a an w)
            "originalGetDouble" -> evaluate (I# (word2Int# (word64ToWord# (castDoubleToWord64# (G.c_mpn_get_d a (word2Int# w) fraction)))))
            "originalEncodeDouble" -> evaluate (I# (word2Int# (word64ToWord# (castDoubleToWord64# (P.intEncodeDouble# (word2Int# w) fraction)))))
            _ -> fail "unknown original GMP request"
      afterLeft <- observe leftBuffer
      afterRight <- observe rightBuffer
      afterOutput <- observe output
      afterRemainder <- observe remainder
      pure ((entry,alias,beforeLeft,beforeRight,nl,nr,fromIntegral word :: Int,fractional,
             outputCount,remainderCount,beforeOutput,beforeRemainder),
            (result,afterLeft,afterRight,afterOutput,afterRemainder))
