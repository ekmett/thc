{-# LANGUAGE MagicHash, UnboxedTuples #-}
module MutableByteArraySizeAudit where
import GHC.Exts

{-# OPAQUE getSizeWorker #-}
getSizeWorker :: MutableByteArray# s -> State# s -> (# State# s, Int# #)
getSizeWorker array state = getSizeofMutableByteArray# array state

-- The deprecated pure operation is used only on a current, stable reference.
{-# OPAQUE pureSizeWorker #-}
pureSizeWorker :: MutableByteArray# s -> Int#
pureSizeWorker array = sizeofMutableByteArray# array

{-# OPAQUE freshSize #-}
freshSize :: Int# -> Int# -> Int#
freshSize _ code = runRW# (\s ->
  case newByteArray# (andI# code 4095#) s of { (# s1, array #) ->
  case getSizeWorker array s1 of { (# _, size #) -> size } })

{-# OPAQUE pureSize #-}
pureSize :: Int# -> Int# -> Int#
pureSize _ code = runRW# (\s ->
  case newByteArray# (andI# code 4095#) s of { (# _, array #) -> pureSizeWorker array })

{-# OPAQUE resizedSizes #-}
resizedSizes :: Int# -> Int# -> Int#
resizedSizes _ code = runRW# (\s ->
  case newByteArray# old s of { (# s1, original #) ->
  case getSizeWorker original s1 of { (# s2, before #) ->
  case resizeMutableByteArray# original middle s2 of { (# s3, first #) ->
  case getSizeWorker first s3 of { (# s4, during #) ->
  case resizeMutableByteArray# first final s4 of { (# s5, second #) ->
  case getSizeWorker second s5 of { (# _, after #) ->
  before *# 65536# +# during *# 256# +# after }}}}}})
 where key = andI# code 1023#
       old = remInt# key 17#
       middle = remInt# (quotInt# key 17#) 17#
       final = remInt# (middle +# 7#) 17#

{-# OPAQUE pureAfterResize #-}
pureAfterResize :: Int# -> Int# -> Int#
pureAfterResize _ code = runRW# (\s ->
  case newByteArray# old s of { (# s1, original #) ->
  case resizeMutableByteArray# original size s1 of { (# _, resized #) -> pureSizeWorker resized } })
 where key = andI# code 1023#
       old = remInt# key 17#
       size = remInt# (quotInt# key 17#) 17#

-- Each query's State result sequences the next operation. Only returned resize
-- references are used, and the observed byte is explicitly initialized.
{-# OPAQUE orderedSize #-}
orderedSize :: Int# -> Int# -> Int#
orderedSize raw code = runRW# (\s ->
  case newByteArray# old s of { (# s1, original #) ->
  case writeWord8Array# original 0# (wordToWord8# (int2Word# raw)) s1 of { s2 ->
  case getSizeWorker original s2 of { (# s3, before #) ->
  case resizeMutableByteArray# original size s3 of { (# s4, resized #) ->
  case getSizeWorker resized s4 of { (# s5, after #) ->
  case writeWord8Array# resized (after -# 1#) (wordToWord8# (int2Word# (raw +# before))) s5 of { s6 ->
  case readWord8Array# resized (after -# 1#) s6 of { (# _, byte #) ->
  before +# after *# 257# +# word2Int# (word8ToWord# byte) *# 65537# }}}}}}})
 where key = andI# code 1023#
       old = 1# +# remInt# key 17#
       size = 1# +# remInt# (quotInt# key 17#) 17#
