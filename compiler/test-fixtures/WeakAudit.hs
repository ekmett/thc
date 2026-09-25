-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash, UnboxedTuples #-}
module WeakAudit where

import GHC.Exts

{-# OPAQUE bottom #-}
bottom :: a
bottom = bottom

-- One native composite: independent weak registrations, a key-capturing action,
-- raw finalize returning (not executing) it, dead/no-finalizer flags, both value
-- levities, and non-strict lifted key/value/action carriers. No GC timing oracle.
{-# OPAQUE weakComposite #-}
weakComposite :: Int# -> Int#
weakComposite input = runRW# (\s0 ->
  case newMutVar# (I# 0#) s0 of { (# s1, key #) ->
  case mkWeak# key (I# input) (\s ->
         case readMutVar# key s of { (# next, I# count #) ->
         case writeMutVar# key (I# (count +# 1#)) next of done -> (# done, () #) }) s1 of { (# s2, weak #) ->
  case mkWeakNoFinalizer# key key s2 of { (# s3, plain #) ->
  case deRefWeak# weak s3 of { (# s4, live, I# value #) ->
  case finalizeWeak# weak s4 of { (# s5, claimed, action #) ->
  case readMutVar# key s5 of { (# s6, I# before #) ->
  case action s6 of { (# s7, () #) ->
  case readMutVar# key s7 of { (# s8, I# after #) ->
  case deRefWeak# weak s8 of { (# s9, dead, _ #) ->
  case finalizeWeak# weak s9 of { (# s10, repeated, _ #) ->
  case deRefWeak# plain s10 of { (# s11, independent, cell #) ->
  case readMutVar# cell s11 of { (# s12, I# unlifted #) ->
  case finalizeWeak# plain s12 of { (# s13, absent, _ #) ->
  case deRefWeak# plain s13 of { (# s14, plainDead, _ #) ->
  case mkWeak# (bottom :: Int) (bottom :: Int) (bottom :: State# RealWorld -> (# State# RealWorld, () #)) s14 of { (# s15, lazy #) ->
  case deRefWeak# lazy s15 of { (# s16, lazyLive, payload #) ->
  case touch# payload s16 of s17 ->
  case finalizeWeak# lazy s17 of { (# s18, lazyClaimed, lazyAction #) ->
  case touch# lazyAction s18 of s19 ->
  case touch# key s19 of _ ->
  value +# live *# 2# +# claimed *# 3# +# before *# 1009# +# after *# 5# +#
    dead *# 1013# +# repeated *# 1019# +# independent *# 7# +# unlifted *# 11# +#
    absent *# 1021# +# plainDead *# 1031# +# lazyLive *# 13# +# lazyClaimed *# 17#
  } } } } } } } } } } } } } } } } })
