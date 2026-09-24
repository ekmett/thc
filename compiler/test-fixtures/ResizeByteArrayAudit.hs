{-# LANGUAGE MagicHash, UnboxedTuples #-}
module ResizeByteArrayAudit where
import GHC.Exts

-- Keep a genuine tuple-returning call across the resize operation.
{-# OPAQUE resizeWorker #-}
resizeWorker :: MutableByteArray# s -> Int# -> State# s -> (# State# s, MutableByteArray# s #)
resizeWorker array size state = resizeMutableByteArray# array size state

{-# INLINE initialize #-}
initialize :: MutableByteArray# s -> Int# -> Int# -> Int# -> State# s -> State# s
initialize array from end seed = go from
 where
  go i state
    | isTrue# (i <# end) = case writeWord8Array# array i (wordToWord8# (int2Word# (seed +# 17# *# i))) state of
        next -> go (i +# 1#) next
    | otherwise = state

{-# INLINE observe #-}
observe :: ByteArray# -> Int#
observe array = go 0# (sizeofByteArray# array)
 where
  go i acc
    | isTrue# (i <# sizeofByteArray# array) = go (i +# 1#) (acc *# 257# +# word2Int# (word8ToWord# (indexWord8Array# array i)))
    | otherwise = acc

{-# OPAQUE resizedBytes #-}
resizedBytes :: Int# -> Int# -> Int#
resizedBytes raw code = runRW# (\s ->
  case newByteArray# old s of { (# s1, original #) ->
  case initialize original 0# old raw s1 of { s2 ->
  case resizeWorker original new s2 of { (# s3, resized #) ->
  -- No access to original occurs after resize, including the equal-size case.
  case initialize resized old new (raw +# 91#) s3 of { s4 ->
  case unsafeFreezeByteArray# resized s4 of { (# _, frozen #) -> observe frozen }
  }}}})
 where key = andI# code 1023#
       old = remInt# key 17#
       new = remInt# (quotInt# key 17#) 17#

-- Resize again, initialize all new bytes, then overwrite the last byte through
-- the returned reference. Prefix observations never read a retired reference.
{-# OPAQUE resizedTwiceWrites #-}
resizedTwiceWrites :: Int# -> Int# -> Int#
resizedTwiceWrites raw code = runRW# (\s ->
  case newByteArray# old s of { (# s1, original #) ->
  case initialize original 0# old raw s1 of { s2 ->
  case resizeWorker original middle s2 of { (# s3, first #) ->
  case initialize first old middle (raw +# 91#) s3 of { s4 ->
  case resizeWorker first final s4 of { (# s5, second #) ->
  case initialize second middle final (raw +# 133#) s5 of { s6 ->
  case (if isTrue# (final ># 0#)
        then writeWord8Array# second (final -# 1#) (wordToWord8# (int2Word# (raw +# 211#))) s6
        else s6) of { s7 ->
  case unsafeFreezeByteArray# second s7 of { (# _, frozen #) -> observe frozen }
  }}}}}}})
 where key = andI# code 1023#
       old = remInt# key 17#
       middle = remInt# (quotInt# key 17#) 17#
       final = remInt# (middle +# 7#) 17#
