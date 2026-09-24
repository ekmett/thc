{-# LANGUAGE MagicHash, UnboxedTuples #-}
module PinnedAddressAudit where

import GHC.Exts
import GHC.IO (IO(..))
import GHC.Word (Word8(W8#))
import GHC.Fingerprint.Type (Fingerprint(..))
import Foreign.Marshal.Alloc (allocaBytesAligned)
import Foreign.Storable (poke, peek, peekByteOff)

-- The same freeze/contents/keepAlive pattern as original allocaBytes. All native
-- accesses are defined: size=0 takes no memory access; other offsets are in range.
-- Address writes intentionally alias the pinned allocation, not string literals.
{-# OPAQUE addressBytes #-}
addressBytes :: ByteArray# -> Int# -> Int# -> Int# -> State# RealWorld
             -> (# State# RealWorld, Int #)
addressBytes bytes size offset raw state = keepAlive# bytes state (\s0 ->
  case size ==# 0# of
    1# -> (# s0, I# (sizeofByteArray# bytes) #)
    _ -> case byteArrayContents# bytes of { base ->
      case plusAddr# (plusAddr# base size) (offset -# size) of { alias ->
      case writeWord8OffAddr# base 0# (wordToWord8# 11##) s0 of { s1 ->
      case writeWord8OffAddr# base (size -# 1#) (wordToWord8# 13##) s1 of { s2 ->
      case writeWord8OffAddr# alias 0# (wordToWord8# (int2Word# raw)) s2 of { s3 ->
      case readWord8OffAddr# base offset s3 of { (# s4, before #) ->
      case writeWord8OffAddr# base offset
             (wordToWord8# (xor# (word8ToWord# before) 128##)) s4 of { s5 ->
      case readWord8OffAddr# alias 0# s5 of { (# s6, after #) ->
      case readWord8OffAddr# base 0# s6 of { (# s7, first #) ->
      case readWord8OffAddr# base (size -# 1#) s7 of { (# s8, lastByte #) ->
        (# s8, I# (size *# 19# +# word2Int# (word8ToWord# before) *# 257#
            +# word2Int# (word8ToWord# after) *# 65537#
            +# word2Int# (word8ToWord# first) *# 17#
            +# word2Int# (word8ToWord# lastByte) *# 23#) #)
      } } } } } } } } } })

pinnedBytes :: Int# -> Int# -> Int# -> Int#
pinnedBytes size offset raw = runRW# (\s0 ->
  case newPinnedByteArray# size s0 of { (# s1, mutable #) ->
  case unsafeFreezeByteArray# mutable s1 of { (# s2, bytes #) ->
  case addressBytes bytes size offset raw s2 of { (# _, I# answer #) -> answer }
  } })

alignedBytes :: Int# -> Int# -> Int# -> Int# -> Int#
alignedBytes size alignment offset raw = runRW# (\s0 ->
  case newAlignedPinnedByteArray# size alignment s0 of { (# s1, mutable #) ->
  case unsafeFreezeByteArray# mutable s1 of { (# s2, bytes #) ->
  case addressBytes bytes size offset raw s2 of { (# _, I# answer #) -> answer }
  } })

-- Explicit State/Word8 continuation result, with a mutation that detects a
-- second continuation invocation and a read that detects incorrect State order.
keepAliveWord8 :: Int# -> Int#
keepAliveWord8 raw = runRW# (\s0 ->
  case newPinnedByteArray# 1# s0 of { (# s1, mutable #) ->
  case writeWord8Array# mutable 0# (wordToWord8# (int2Word# raw)) s1 of { s2 ->
  case unsafeFreezeByteArray# mutable s2 of { (# s3, bytes #) ->
  case byteArrayContents# bytes of { address ->
  case keepAlive# bytes s3 (\s ->
      case readWord8OffAddr# address 0# s of { (# t, before #) ->
      case writeWord8OffAddr# address 0# (plusWord8# before (wordToWord8# 7##)) t of { u ->
      (# u, before #) } }) of { (# s4, old #) ->
  case readWord8OffAddr# address 0# s4 of { (# _, now #) ->
    word2Int# (word8ToWord# old) *# 257# +# word2Int# (word8ToWord# now)
  } } } } } })

data Kept = Kept
{-# OPAQUE keptBottom #-}
keptBottom :: Kept
keptBottom = keptBottom

-- The first argument is deliberately bottom. Native GHC must never enter it.
-- State/lifted Int continuation result is distinct from the Word8 case above.
keepAliveLazy :: Int# -> Int#
keepAliveLazy raw = runRW# (\s0 ->
  case newPinnedByteArray# 1# s0 of { (# s1, mutable #) ->
  case writeWord8Array# mutable 0# (wordToWord8# (int2Word# raw)) s1 of { s2 ->
  case unsafeFreezeByteArray# mutable s2 of { (# s3, bytes #) ->
  case byteArrayContents# bytes of { address ->
  case keepAlive# keptBottom s3 (\s ->
      case readWord8OffAddr# address 0# s of { (# t, before #) ->
      case writeWord8OffAddr# address 0# (plusWord8# before (wordToWord8# 11##)) t of { u ->
      (# u, I# (word2Int# (word8ToWord# before)) #) } }) of { (# s4, I# old #) ->
  case readWord8OffAddr# address 0# s4 of { (# _, now #) ->
    old *# 257# +# word2Int# (word8ToWord# now)
  } } } } } })

-- Primitive conformance to the original Storable Fingerprint layout, NOT a
-- replacement body or claim that the public Storable dictionary is executable.
-- The separate public roots below use that actual installed dictionary.
fingerprintByte :: Int# -> Int# -> Int# -> Int#
fingerprintByte high low selector = runRW# (\s0 ->
  case newAlignedPinnedByteArray# 16# 8# s0 of { (# s1, mutable #) ->
  case unsafeFreezeByteArray# mutable s1 of { (# s2, bytes #) ->
  case byteArrayContents# bytes of { address ->
    let writeHalf word start state =
          let loop index current = case index ==# 8# of
                1# -> current
                _ -> case writeWord8OffAddr# address (start +# index)
                            (wordToWord8# (uncheckedShiftRL# word ((7# -# index) *# 8#))) current of
                       next -> loop (index +# 1#) next
          in loop 0# state
    in case writeHalf (int2Word# high) 0# s2 of { s3 ->
       case writeHalf (int2Word# low) 8# s3 of { s4 ->
       case keepAlive# bytes s4 (\s -> readWord8OffAddr# address selector s) of {
         (# _, value #) -> word2Int# (word8ToWord# value)
       } } }
  } } })

-- Genuine installed public API native/control roots. Their original missing
-- Storable source workers remain an explicit strict frontier until supplied.
publicFingerprintByte :: Int# -> Int# -> Int# -> Int#
publicFingerprintByte high low selector =
  case runRW# (\s -> case action of IO f -> f s) of
    (# _, W8# value #) -> word2Int# (word8ToWord# value)
  where
    action = allocaBytesAligned 16 8 (\pointer -> do
      poke pointer (Fingerprint (fromIntegral (I# high)) (fromIntegral (I# low)))
      peekByteOff pointer (I# selector) :: IO Word8)

publicFingerprintRoundtrip :: Int# -> Int# -> Int# -> Int#
publicFingerprintRoundtrip high low selector =
  case runRW# (\s -> case action of IO f -> f s) of (# _, I# answer #) -> answer
  where
    action = allocaBytesAligned 16 8 (\pointer -> do
      poke pointer (Fingerprint (fromIntegral (I# high)) (fromIntegral (I# low)))
      Fingerprint first second <- peek pointer
      pure (fromIntegral (if isTrue# (selector ==# 0#) then first else second)))
