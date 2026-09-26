-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE CApiFFI, ForeignFunctionInterface, MagicHash, UnliftedFFITypes #-}
module CapiMix (mixed, mixedProbe#, wideProbe#, word16Probe#, staticPointerProbe#) where
import Foreign.Marshal.Alloc (allocaBytes)
import Foreign.Storable (poke)
import GHC.Exts (Addr#, Int(I#), Int#, Word(W#), Word#, Word8#, Word32#, Word64#,
  int2Word#, plusWord#, wordToWord8#, wordToWord32#, word64ToWord#)
import GHC.Ptr (Ptr(Ptr))
import Data.Word (Word64)

-- Same boundary as original crypton ChaCha: CAPI includes a header whose
-- neighboring direct ccall has an opaque struct pointer and narrower C args.
foreign import capi unsafe "mixed-header.h archive_state_for"
  stateFor :: Int# -> Addr#
foreign import capi unsafe "mixed-header.h archive_state_bias"
  stateBias :: Addr# -> Word64#
foreign import ccall unsafe "archive_header_mix"
  headerMix :: Addr# -> Word8# -> Word32# -> Word64#
foreign import ccall unsafe "archive_header_mix_wide"
  headerMixWide :: Addr# -> Int# -> Int# -> Word64#
foreign import ccall unsafe "archive_header_mix16"
  word16Probe# :: Addr# -> Int# -> Int# -> Int#

mixedProbe# :: Addr# -> Int# -> Int# -> Word#
mixedProbe# state rounds keylen = plusWord# (word64ToWord# (stateBias state))
  (word64ToWord# (headerMix state (wordToWord8# (int2Word# rounds)) (wordToWord32# (int2Word# keylen))))

wideProbe# :: Addr# -> Int# -> Int# -> Word#
wideProbe# state rounds keylen = plusWord# (word64ToWord# (stateBias state))
  (word64ToWord# (headerMixWide state rounds keylen))

-- Exercise a genuine returned static C pointer without granting guest byte
-- ownership; the pointer must remain usable by its original native functions.
staticPointerProbe# :: Int# -> Int# -> Word#
staticPointerProbe# rounds keylen = mixedProbe# (stateFor 0#) rounds keylen

mixed :: Int -> Int -> IO (Word,Word,Int,Word)
mixed (I# rounds) (I# keylen) = allocaBytes 8 $ \pointer@(Ptr state) -> do
  poke pointer (7 :: Word64)
  pure (W# (mixedProbe# state rounds keylen), W# (wideProbe# state rounds keylen),
    I# (word16Probe# state rounds keylen), W# (staticPointerProbe# rounds keylen))
