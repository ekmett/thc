-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash, UnboxedTuples #-}
module BoxedCasAudit where

import GHC.Exts

data Box = Box Int#

{-# OPAQUE bottomBox #-}
bottomBox :: Int# -> Box
bottomBox n = bottomBox (n +# 1#)

-- Tickets are obtained from the cell and used without evaluating their payloads.
-- A failed compare returns the observed ticket, suitable for the next compare.

{-# OPAQUE arrayCas #-}
arrayCas :: Int# -> Int#
arrayCas n = runRW# (\s0 ->
  case newArray# 1# (Box n) s0 of { (# s1, array #) ->
  case readArray# array 0# s1 of { (# s2, ticket #) ->
  case casArray# array 0# ticket (Box (n +# 1#)) s2 of { (# s3, ok, changed #) ->
  case casArray# array 0# ticket (bottomBox n) s3 of { (# s4, failed, observed #) ->
  case casArray# array 0# observed ticket s4 of { (# s5, retry, restored #) ->
  case casArray# array 0# restored (bottomBox n) s5 of { (# _, lazy, _ #) ->
  case changed of { Box x -> case observed of { Box y -> case restored of { Box z ->
    x +# y +# z +# (ok *# 10#) +# (failed *# 100#) +# (retry *# 1000#) +# (lazy *# 10000#)
  } } } } } } } } })

{-# OPAQUE arrayCasUnlifted #-}
arrayCasUnlifted :: Int# -> Int#
arrayCasUnlifted n = runRW# (\s0 ->
  case newMutVar# (Box n) s0 of { (# s1, first #) ->
  case newMutVar# (Box (n +# 1#)) s1 of { (# s2, second #) ->
  case newArray# 1# first s2 of { (# s3, array #) ->
  case readArray# array 0# s3 of { (# s4, ticket #) ->
  case casArray# array 0# ticket second s4 of { (# s5, ok, changed #) ->
  case casArray# array 0# ticket first s5 of { (# s6, failed, observed #) ->
  case casArray# array 0# observed first s6 of { (# s7, retry, restored #) ->
  case readMutVar# changed s7 of { (# s8, Box x #) ->
  case readMutVar# observed s8 of { (# s9, Box y #) ->
  case readMutVar# restored s9 of { (# _, Box z #) ->
    x +# y +# z +# (ok *# 10#) +# (failed *# 100#) +# (retry *# 1000#)
  } } } } } } } } } })

{-# OPAQUE smallCas #-}
smallCas :: Int# -> Int#
smallCas n = runRW# (\s0 ->
  case newSmallArray# 1# (Box n) s0 of { (# s1, array #) ->
  case readSmallArray# array 0# s1 of { (# s2, ticket #) ->
  case casSmallArray# array 0# ticket (Box (n +# 1#)) s2 of { (# s3, ok, changed #) ->
  case casSmallArray# array 0# ticket (bottomBox n) s3 of { (# s4, failed, observed #) ->
  case casSmallArray# array 0# observed ticket s4 of { (# s5, retry, restored #) ->
  case casSmallArray# array 0# restored (bottomBox n) s5 of { (# _, lazy, _ #) ->
  case changed of { Box x -> case observed of { Box y -> case restored of { Box z ->
    x +# y +# z +# (ok *# 10#) +# (failed *# 100#) +# (retry *# 1000#) +# (lazy *# 10000#)
  } } } } } } } } })

{-# OPAQUE smallCasUnlifted #-}
smallCasUnlifted :: Int# -> Int#
smallCasUnlifted n = runRW# (\s0 ->
  case newMutVar# (Box n) s0 of { (# s1, first #) ->
  case newMutVar# (Box (n +# 1#)) s1 of { (# s2, second #) ->
  case newSmallArray# 1# first s2 of { (# s3, array #) ->
  case readSmallArray# array 0# s3 of { (# s4, ticket #) ->
  case casSmallArray# array 0# ticket second s4 of { (# s5, ok, changed #) ->
  case casSmallArray# array 0# ticket first s5 of { (# s6, failed, observed #) ->
  case casSmallArray# array 0# observed first s6 of { (# s7, retry, restored #) ->
  case readMutVar# changed s7 of { (# s8, Box x #) ->
  case readMutVar# observed s8 of { (# s9, Box y #) ->
  case readMutVar# restored s9 of { (# _, Box z #) ->
    x +# y +# z +# (ok *# 10#) +# (failed *# 100#) +# (retry *# 1000#)
  } } } } } } } } } })

{-# OPAQUE varCas #-}
varCas :: Int# -> Int#
varCas n = runRW# (\s0 ->
  case newMutVar# (Box n) s0 of { (# s1, cell #) ->
  case readMutVar# cell s1 of { (# s2, ticket #) ->
  case casMutVar# cell ticket (Box (n +# 1#)) s2 of { (# s3, ok, changed #) ->
  case casMutVar# cell ticket (bottomBox n) s3 of { (# s4, failed, observed #) ->
  case casMutVar# cell observed ticket s4 of { (# s5, retry, restored #) ->
  case casMutVar# cell restored (bottomBox n) s5 of { (# _, lazy, _ #) ->
  case changed of { Box x -> case observed of { Box y -> case restored of { Box z ->
    x +# y +# z +# (ok *# 10#) +# (failed *# 100#) +# (retry *# 1000#) +# (lazy *# 10000#)
  } } } } } } } } })

{-# OPAQUE varCasUnlifted #-}
varCasUnlifted :: Int# -> Int#
varCasUnlifted n = runRW# (\s0 ->
  case newMutVar# (Box n) s0 of { (# s1, first #) ->
  case newMutVar# (Box (n +# 1#)) s1 of { (# s2, second #) ->
  case newMutVar# first s2 of { (# s3, cell #) ->
  case readMutVar# cell s3 of { (# s4, ticket #) ->
  case casMutVar# cell ticket second s4 of { (# s5, ok, changed #) ->
  case casMutVar# cell ticket first s5 of { (# s6, failed, observed #) ->
  case casMutVar# cell observed first s6 of { (# s7, retry, restored #) ->
  case readMutVar# changed s7 of { (# s8, Box x #) ->
  case readMutVar# observed s8 of { (# s9, Box y #) ->
  case readMutVar# restored s9 of { (# _, Box z #) ->
    x +# y +# z +# (ok *# 10#) +# (failed *# 100#) +# (retry *# 1000#)
  } } } } } } } } } })

{-# OPAQUE modifyValue #-}
modifyValue :: Int# -> Int#
modifyValue n = runRW# (\s0 ->
  case newMutVar# (Box n) s0 of { (# s1, cell #) ->
  case atomicModifyMutVar_# cell (\(Box x) -> Box (x +# 1#)) s1 of { (# s2, old, result #) ->
  case casMutVar# cell result old s2 of { (# _, ok, restored #) ->
  case old of { Box x -> case result of { Box y -> case restored of { Box z ->
    x +# y +# z +# (ok *# 100#)
  } } } } } })

{-# OPAQUE modifyLazy #-}
modifyLazy :: Int# -> Int#
modifyLazy n = runRW# (\s0 ->
  case newMutVar# (bottomBox n) s0 of { (# s1, cell #) ->
  case atomicModifyMutVar_# cell (\_ -> Box (n +# 7#)) s1 of { (# s2, _, result #) ->
  case readMutVar# cell s2 of { (# _, stored #) ->
  case result of { Box x -> case stored of { Box y -> x +# y } }
  } } })

{-# OPAQUE bottomFunction #-}
bottomFunction :: Int# -> Box -> Box
bottomFunction n = bottomFunction (n +# 1#)

{-# OPAQUE modifyBottom #-}
modifyBottom :: Int# -> Int#
modifyBottom n = runRW# (\s0 ->
  case newMutVar# (Box n) s0 of { (# s1, cell #) ->
  case atomicModifyMutVar_# cell (bottomFunction n) s1 of { (# _, old, _ #) ->
  case old of { Box x -> x }
  } })
