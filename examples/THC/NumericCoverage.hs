{-# LANGUAGE MagicHash, NoImplicitPrelude #-}
-- Bounded numerical workloads for differential execution of optimized GHC Core.
-- All entries accept every 64-bit Int#. Shift counts are in range, division
-- uses +/-10, and Unicode construction only uses valid scalar values.
module THC.NumericCoverage
  ( wordMixer, signedRadix, packedSamples, unicodeChecksum ) where

import GHC.Exts
  ( Int#, Word#, Char#, (+#), (-#), (*#), (==#), (<#), (<=#)
  , int2Word#, word2Int#, plusWord#, minusWord#, timesWord#
  , and#, or#, xor#, not#, andI#, xorI#
  , uncheckedShiftL#, uncheckedShiftRL#
  , uncheckedIShiftRA#, uncheckedIShiftRL#
  , quotInt#, remInt#, negateInt#
  , narrow8Int#, narrow16Int#, narrow32Int#
  , chr#, ord#, eqChar#, ltChar#, leChar#
  )

-- A short, deliberately noncryptographic word mixer. Arithmetic crosses the
-- Word sign boundary, while rotations join bits from both ends of the word.
wordMixer :: Int# -> Int#
wordMixer input = word2Int# (go 12# (int2Word# input) 0xffffffffffffffff##)
  where
    go rounds state checksum = case rounds <=# 0# of
      1# -> minusWord# checksum state
      _ ->
        let shift = andI# (word2Int# state) 31# +# 1#
            advanced = plusWord# (timesWord# state 0x9e3779b97f4a7c15##)
                                0xda942042e4dd58b5##
            rotated = or# (uncheckedShiftL# advanced shift)
                          (uncheckedShiftRL# advanced (64# -# shift))
            next = xor# rotated (uncheckedShiftRL# state 17#)
            selected = and# (not# state) 0xa5a5a5a5a5a5a5a5##
        in go (rounds -# 1#) next
              (plusWord# (xor# checksum selected) next)

-- Encode a signed number by repeatedly extracting base +/-10 digits. The
-- alternating divisor covers both quotient signs and signed remainders;
-- +/-10 avoids division by zero and the minBound / -1 exceptional boundary.
-- Arithmetic and logical shifts give different prefixes for negative inputs.
signedRadix :: Int# -> Int#
signedRadix input = go 22# input
  (xorI# (uncheckedIShiftRA# input 5#) (uncheckedIShiftRL# input 5#))
  where
    go remaining number checksum = case remaining <=# 0# of
      1# -> checksum
      _ ->
        let divisor = case andI# remaining 1# of
              0# -> 10#
              _ -> negateInt# 10#
            quotient = quotInt# number divisor
            digit = remInt# number divisor
        in go (remaining -# 1#) quotient
              (checksum *# 37# +# digit *# remaining +# quotient)

data Memo = Memo Int#
-- Primitive registers alternate with references, including an escaping closure.
data Packet = Packet Word# Memo Int# (Int# -> Int#) Int# Int#

-- The packet boundary deliberately preserves decoding, mixed representation
-- constructor fields, and a closure retaining a Word# plus a lifted Memo.
{-# OPAQUE decodePacket #-}
decodePacket :: Int# -> Memo -> Packet
decodePacket bits memo =
  let byte = narrow8Int# bits
      short = narrow16Int# (uncheckedIShiftRL# bits 8#)
      sample = narrow32Int# (uncheckedIShiftRL# bits 16#)
      word = int2Word# bits
      finish delta = case memo of
        Memo previous -> previous +# delta +# word2Int# (uncheckedShiftRL# word 47#)
  in Packet word memo byte finish short sample

{-# OPAQUE consumePacket #-}
consumePacket :: Packet -> Int#
consumePacket (Packet word memo byte finish short sample) = case memo of
  Memo previous -> finish
    (byte *# 3# +# short *# 5# +# sample *# 7# +#
     andI# previous 255# +# word2Int# (and# word 255##))

-- A fixed block of packed sensor samples, decoded with signed 8/16/32-bit
-- extension and a rolling predictor. Input is raw packed bits, not a length.
packedSamples :: Int# -> Int#
packedSamples input = go 12# input 0#
  where
    go remaining state checksum = case remaining <=# 0# of
      1# -> checksum
      _ -> go (remaining -# 1#)
              (state *# 6364136223846793005# +# 1442695040888963407#)
              (consumePacket (decodePacket state (Memo checksum)))

data Glyphs = End | Glyph Char# Glyphs

-- Indexing this compact alphabet exercises literal cases and non-ASCII
-- Char# values including U+10FFFF. No surrogate or invalid chr# input occurs.
{-# OPAQUE alphabetPoint #-}
alphabetPoint :: Int# -> Int#
alphabetPoint index = case andI# index 7# of
  0# -> 0#
  1# -> 65#
  2# -> 233#
  3# -> 937#
  4# -> 8364#
  5# -> 20013#
  6# -> 128512#
  _ -> 1114111#

-- The list boundary keeps character constructor fields and cases in optimized
-- Core; warm inputs visit only NUL; held-out inputs introduce new character categories.
{-# OPAQUE buildGlyphs #-}
buildGlyphs :: Int# -> Int# -> Glyphs
buildGlyphs count seed = case count <=# 0# of
  1# -> End
  _ -> Glyph (chr# (alphabetPoint seed))
             (buildGlyphs (count -# 1#) (seed +# 3#))

unicodeChecksum :: Int# -> Int#
unicodeChecksum input = scan (buildGlyphs (1# +# andI# input 3#) input) 0#
  where
    scan glyphs checksum = case glyphs of
      End -> checksum
      Glyph character rest ->
        let category = case eqChar# character '\0'# of
              1# -> 1#
              _ -> case ltChar# character '\x80'# of
                1# -> 2#
                _ -> case leChar# character '\xff'# of
                  1# -> 3#
                  _ -> case ltChar# character '\x10000'# of
                    1# -> 5#
                    _ -> 7#
        in scan rest (checksum *# 33# +# ord# character *# category)
