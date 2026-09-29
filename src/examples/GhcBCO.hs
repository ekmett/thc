-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples #-}

-- |
-- Module      : GhcBCO
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC-specific primitive types and operations
--
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

-- GHC case continuations have zero arity but describe their saved stack.
-- Both return-frame headers remain present until the continuation slides them.
{-# OPAQUE bcoCase #-}
bcoCase :: Int# -> Int#
bcoCase n = runRW# (\s ->
  case words16 8# [38,0,1,38,1,2,90,61] s of { (# s1, contCode #) ->
  case words64 0# [] s1 of { (# s2, noLits #) ->
  case refs 0# [] s2 of { (# s3, noPtrs #) ->
  case words64 2# [1,1] s3 of { (# s4, bitmap #) ->
  case newBCO# contCode noLits noPtrs 0# bitmap s4 of { (# s5, cont #) ->
  case words16 6# [14,0,25,0,1,61] s5 of { (# s6, code #) ->
  case words64 1# [7] s6 of { (# s7, lits #) ->
  case refs 1# [unsafeCoerce# cont] s7 of { (# s8, ptrs #) ->
  case newBCO# code lits ptrs 1# bitmap s8 of { (# _, bco #) ->
  (unsafeCoerce# bco :: Int# -> Int#) n } } } } } } } } })

countWords :: [a] -> Int#
countWords [] = 0#
countWords (_ : xs) = 1# +# countWords xs

{-# OPAQUE makeBCO #-}
makeBCO :: [Int] -> [Int] -> [Any] -> Int# -> [Int] -> State# RealWorld -> (# State# RealWorld, BCO #)
makeBCO instructions literals pointers arity bitmap s =
  case words16 (countWords instructions) instructions s of { (# s1, code #) ->
  case words64 (countWords literals) literals s1 of { (# s2, lits #) ->
  case refs (countWords pointers) pointers s2 of { (# s3, ptrs #) ->
  case words64 (countWords bitmap) bitmap s3 of { (# s4, bits #) ->
  newBCO# code lits ptrs arity bits s4 } } } }

{-# OPAQUE bcoCaseNested #-}
bcoCaseNested :: Int# -> Int#
bcoCaseNested n = runRW# (\s ->
  case makeBCO [38,0,1,38,1,2,90,90,61] [] [] 0# [2,3] s of { (# s1, inner #) ->
  case makeBCO [38,0,1,38,1,2,14,0,25,0,1,61] [3] [unsafeCoerce# inner] 0# [1,1] s1 of { (# s2, outer #) ->
  case makeBCO [14,0,25,0,1,61] [7] [unsafeCoerce# outer] 1# [1,1] s2 of { (# _, bco #) ->
  (unsafeCoerce# bco :: Int# -> Int#) n } } })

{-# OPAQUE bcoCasePointer #-}
bcoCasePointer :: Int# -> Int#
bcoCasePointer n = case runRW# (\s ->
  case makeBCO [38,0,1,38,1,2,60] [] [] 0# [0] s of { (# s1, cont #) ->
  case makeBCO [13,0,11,1,58] [] [unsafeCoerce# cont, unsafeCoerce# (I# n)] 0# [0] s1 of { (# _, bco #) ->
  case mkApUpd0# bco of { (# value #) -> value } } }) of I# result -> result

{-# OPAQUE typedCase #-}
typedCase :: Int# -> Int# -> Int# -> Int# -> Int#
typedCase push ret bits n = runRW# (\s ->
  case makeBCO [38,0,1,38,1,2,61] [] [] 0# [0] s of { (# s1, cont #) ->
  case makeBCO [38,0,1,I# push,0,25,0,1,I# ret] [I# bits] [unsafeCoerce# cont] 1# [1,1] s1 of { (# _, bco #) ->
  (unsafeCoerce# bco :: Int# -> Int#) n } })

{-# OPAQUE bcoCaseFloat #-}
bcoCaseFloat :: Int# -> Int#
bcoCaseFloat n = runRW# (\s ->
  case makeBCO [38,0,1,38,1,2,61] [] [] 0# [0] s of { (# s1, cont #) ->
  -- GHC pushLiteral pads a Float# to a full stack word on this 64-bit ABI.
  case makeBCO [38,0,1,15,0,21,24,0,62] [2143294004] [unsafeCoerce# cont] 1# [1,1] s1 of { (# _, bco #) ->
  (unsafeCoerce# bco :: Int# -> Int#) n } })

{-# OPAQUE bcoCaseDouble #-}
bcoCaseDouble :: Int# -> Int#
bcoCaseDouble n = typedCase 16# 63# 9221120237041095220# n

{-# OPAQUE bcoCaseLong #-}
bcoCaseLong :: Int# -> Int#
bcoCaseLong n = typedCase 17# 64# n n

{-# OPAQUE bcoCaseVoid #-}
bcoCaseVoid :: Int# -> Int#
bcoCaseVoid n = runRW# (\s ->
  case makeBCO [38,0,1,38,0,2,61] [] [] 0# [1,1] s of { (# s1, cont #) ->
  case makeBCO [18,0,65] [] [unsafeCoerce# cont] 1# [1,1] s1 of { (# _, bco #) ->
  (unsafeCoerce# bco :: Int# -> Int#) n } })

{-# OPAQUE packedCase #-}
packedCase :: [Int] -> Int# -> Int# -> Int#
packedCase instructions bits n = runRW# (\s ->
  case makeBCO instructions [I# bits] [] 1# [1,1] s of { (# _, bco #) ->
  (unsafeCoerce# bco :: Int# -> Int#) n })

{-# OPAQUE bcoPacked8 #-}
bcoPacked8 :: Int# -> Int#
bcoPacked8 n = packedCase [38,0,1,21,20,19,22,0,21,20,19,5,7,8,0,90,90,61] 171# n
{-# OPAQUE bcoPacked16 #-}
bcoPacked16 :: Int# -> Int#
bcoPacked16 n = packedCase [38,0,1,21,20,23,0,21,20,6,6,9,0,90,90,61] 52719# n
{-# OPAQUE bcoPacked32 #-}
bcoPacked32 :: Int# -> Int#
bcoPacked32 n = packedCase [38,0,1,21,24,0,21,7,4,10,0,90,90,61] 2309737967# n

-- The internal tuple uses two scalar registers: the first two bits of
-- allArgRegsCover, with no native stack spill. No RTS addresses are invented.
{-# OPAQUE bcoCaseTuple #-}
bcoCaseTuple :: Int# -> Int#
bcoCaseTuple n = runRW# (\s ->
  case makeBCO [38,0,1,69] [] [] 0# [3,7] s of { (# s1, tuple #) ->
  case makeBCO [38,0,3,38,2,4,90,38,1,1,61] [] [] 0# [3,5] s1 of { (# s2, cont #) ->
  case makeBCO [70,0,0,1,25,1,1,2,5,25,0,1,11,1,69] [3,7]
    [unsafeCoerce# cont, unsafeCoerce# tuple] 1# [1,1] s2 of { (# _, bco #) ->
  (unsafeCoerce# bco :: Int# -> Int#) n } } })

{-# OPAQUE bcoCaseTupleCall #-}
bcoCaseTupleCall :: Int# -> Int#
bcoCaseTupleCall n = case runRW# (\s ->
  -- call_info is nonpointer, then the returned pointer and nonpointer word.
  case makeBCO [38,0,1,69] [] [] 0# [3,5] s of { (# s1, tuple #) ->
  case makeBCO [38,0,3,38,2,4,38,1,1,60] [] [] 0# [2,1] s1 of { (# s2, cont #) ->
  case makeBCO [25,1,1,2,1,38,2,1,25,0,1,11,0,69] [3,7]
    [unsafeCoerce# tuple] 1# [1,0] s2 of { (# s3, worker #) ->
  case makeBCO [70,0,0,1,11,2,31,11,3,58] [3]
    [unsafeCoerce# cont, unsafeCoerce# tuple, unsafeCoerce# (I# n), unsafeCoerce# worker] 0# [0] s3 of { (# _, bco #) ->
  case mkApUpd0# bco of { (# value #) -> value } } } } }) of I# result -> result

{-# OPAQUE bcoCaseTupleOverapply #-}
bcoCaseTupleOverapply :: Int# -> Int#
bcoCaseTupleOverapply n = case runRW# (\s ->
  -- call_info is nonpointer, then the returned pointer and nonpointer word.
  case makeBCO [38,0,1,69] [] [] 0# [3,5] s of { (# s1, tuple #) ->
  case makeBCO [38,0,3,38,2,4,38,1,1,60] [] [] 0# [2,1] s1 of { (# s2, cont #) ->
  case makeBCO [25,1,1,2,1,38,2,1,25,0,1,11,0,69] [3,7]
    [unsafeCoerce# tuple] 1# [1,0] s2 of { (# s3, worker #) ->
  case makeBCO [38,0,1,11,0,60] [] [unsafeCoerce# worker] 1# [1,0] s3 of { (# s4, first #) ->
  case makeBCO [70,0,0,1,11,2,11,2,32,11,3,58] [3]
    [unsafeCoerce# cont, unsafeCoerce# tuple, unsafeCoerce# (I# n), unsafeCoerce# first] 0# [0] s4 of { (# _, bco #) ->
  case mkApUpd0# bco of { (# value #) -> value } } } } } }) of I# result -> result
