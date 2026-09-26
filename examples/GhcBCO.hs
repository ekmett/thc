-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples #-}
-- GHC 9.14.1's actual BCO wire format, built with ordinary primops. This is
-- intentionally not THC Core bytecode and needs no native pointer fabrication.
module GhcBCO where
import GHC.Exts

words16 :: Int# -> [Int] -> State# RealWorld -> (# State# RealWorld, ByteArray# #)
words16 size values s = case newByteArray# (size *# 2#) s of
  (# s1, a #) -> let
    go i [] st = unsafeFreezeByteArray# a st
    go i (I# v : vs) st = go (i +# 1#) vs (writeWord16Array# a i (wordToWord16# (int2Word# v)) st)
    in go 0# values s1

words64 :: Int# -> [Int] -> State# RealWorld -> (# State# RealWorld, ByteArray# #)
words64 size values s = case newByteArray# (size *# 8#) s of
  (# s1, a #) -> let
    go i [] st = unsafeFreezeByteArray# a st
    go i (I# v : vs) st = go (i +# 1#) vs (writeIntArray# a i v st)
    in go 0# values s1

refs :: Int# -> [Any] -> State# RealWorld -> (# State# RealWorld, Array# Any #)
refs size values s = case newArray# size (unsafeCoerce# (I# 0#)) s of
  (# s1, a #) -> let
    go i [] st = unsafeFreezeArray# a st
    go i (v : vs) st = go (i +# 1#) vs (writeArray# a i v st)
    in go 0# values s1

{-# OPAQUE plusSeven #-}
plusSeven :: Int -> Int
plusSeven (I# n) = I# (n +# 7#)

{-# OPAQUE combine #-}
combine :: Int -> Int -> Int
combine (I# a) (I# b) = I# (a *# 10# +# b)

{-# OPAQUE bcoConstant #-}
bcoConstant :: Int# -> Int#
bcoConstant n = case runRW# (\s ->
  case words16 3# [11,0,58] s of { (# s1, code #) ->
  case words64 0# [] s1 of { (# s2, lits #) ->
  case refs 1# [unsafeCoerce# (I# n)] s2 of { (# s3, ptrs #) ->
  case words64 1# [0] s3 of { (# s4, bitmap #) ->
  case newBCO# code lits ptrs 0# bitmap s4 of { (# _, bco #) ->
  case mkApUpd0# bco of { (# value #) -> value } } } } } }) of I# result -> result

{-# OPAQUE bcoApply #-}
bcoApply :: Int# -> Int#
bcoApply n = case runRW# (\s ->
  case words16 6# [11,1,31,11,0,58] s of { (# s1, code #) ->
  case words64 0# [] s1 of { (# s2, lits #) ->
  case refs 2# [unsafeCoerce# plusSeven, unsafeCoerce# (I# n)] s2 of { (# s3, ptrs #) ->
  case words64 1# [0] s3 of { (# s4, bitmap #) ->
  case newBCO# code lits ptrs 0# bitmap s4 of { (# _, bco #) ->
  case mkApUpd0# bco of { (# value #) -> value } } } } } }) of I# result -> result

{-# OPAQUE bcoApplyTwo #-}
bcoApplyTwo :: Int# -> Int#
bcoApplyTwo n = case runRW# (\s ->
  case words16 8# [11,2,11,1,32,11,0,58] s of { (# s1, code #) ->
  case words64 0# [] s1 of { (# s2, lits #) ->
  case refs 3# [unsafeCoerce# combine, unsafeCoerce# (I# n), unsafeCoerce# (I# 3#)] s2 of { (# s3, ptrs #) ->
  case words64 1# [0] s3 of { (# s4, bitmap #) ->
  case newBCO# code lits ptrs 0# bitmap s4 of { (# _, bco #) ->
  case mkApUpd0# bco of { (# value #) -> value } } } } } }) of I# result -> result

{-# OPAQUE bcoFunction #-}
bcoFunction :: Int# -> Int#
bcoFunction n = case runRW# (\s ->
  case words16 6# [2,0,38,1,2,58] s of { (# s1, code #) ->
  case words64 0# [] s1 of { (# s2, lits #) ->
  case refs 0# [] s2 of { (# s3, ptrs #) ->
  case words64 2# [2,0] s3 of { (# s4, bitmap #) ->
  case newBCO# code lits ptrs 2# bitmap s4 of { (# _, bco #) ->
  (unsafeCoerce# bco :: Int -> Int -> Int) (I# n) (I# 17#) } } } } }) of I# result -> result

{-# OPAQUE bcoArithmetic #-}
bcoArithmetic :: Int# -> Int#
bcoArithmetic n = runRW# (\s ->
  -- Stack begins with the argument. Push literal 100, subtract argument, return.
  case words16 5# [25,0,1,91,61] s of { (# s1, code #) ->
  case words64 1# [100] s1 of { (# s2, lits #) ->
  case refs 0# [] s2 of { (# s3, ptrs #) ->
  case words64 2# [1,1] s3 of { (# s4, bitmap #) ->
  case newBCO# code lits ptrs 1# bitmap s4 of { (# _, bco #) ->
  (unsafeCoerce# bco :: Int# -> Int#) n } } } } })

{-# OPAQUE bcoBranch #-}
bcoBranch :: Int# -> Int#
bcoBranch n = case runRW# (\s ->
  case words16 18# [25,0,1,46,1,12,38,0,1,11,0,58,38,0,1,11,1,58] s of { (# s1, code #) ->
  case words64 2# [I# n,0] s1 of { (# s2, lits #) ->
  case refs 2# [unsafeCoerce# (I# (-1#)), unsafeCoerce# (I# 1#)] s2 of { (# s3, ptrs #) ->
  case words64 1# [0] s3 of { (# s4, bitmap #) ->
  case newBCO# code lits ptrs 0# bitmap s4 of { (# _, bco #) ->
  case mkApUpd0# bco of { (# value #) -> value } } } } } }) of I# result -> result

{-# OPAQUE bcoLargeOperand #-}
bcoLargeOperand :: Int# -> Int#
bcoLargeOperand n = case runRW# (\s ->
  case words16 6# [32779,0,0,0,0,58] s of { (# s1, code #) ->
  case words64 0# [] s1 of { (# s2, lits #) ->
  case refs 1# [unsafeCoerce# (I# n)] s2 of { (# s3, ptrs #) ->
  case words64 1# [0] s3 of { (# s4, bitmap #) ->
  case newBCO# code lits ptrs 0# bitmap s4 of { (# _, bco #) ->
  case mkApUpd0# bco of { (# value #) -> value } } } } } }) of I# result -> result

{-# OPAQUE tick #-}
tick :: MutVar# RealWorld Int -> Int -> Int
tick cell (I# n) = runRW# (\s -> case readMutVar# cell s of
  (# s1, I# old #) -> case writeMutVar# cell (I# (old +# 1#)) s1 of
    _ -> I# (n +# old))

{-# OPAQUE bcoSharing #-}
bcoSharing :: Int# -> Int#
bcoSharing n = runRW# (\s ->
  case newMutVar# (I# 0#) s of { (# s0, cell #) ->
  case words16 6# [11,1,31,11,0,58] s0 of { (# s1, code #) ->
  case words64 0# [] s1 of { (# s2, lits #) ->
  case refs 2# [unsafeCoerce# (tick cell), unsafeCoerce# (I# n)] s2 of { (# s3, ptrs #) ->
  case words64 1# [0] s3 of { (# s4, bitmap #) ->
  case newBCO# code lits ptrs 0# bitmap s4 of { (# s5, bco #) ->
  case mkApUpd0# bco of { (# value #) ->
  case value of { I# a -> case value of { I# b ->
  case readMutVar# cell s5 of { (# _, I# count #) -> a +# b +# count } } } } } } } } } })
