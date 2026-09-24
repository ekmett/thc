-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples #-}
module IoMainPapAudit (goodMain, badMain) where

import GHC.Exts
import GHC.IO (IO(..))

data Failure = WrongEffect | ExpectedFailure

-- The fixed prefix has two arguments. OPAQUE preserves a genuine global PAP
-- at -O2, with exactly the RealWorld state binder left unapplied after erasing
-- the IO newtype. The stateful write/read and their checked result are essential:
-- merely loading either entry is not equivalent to running the IO action.
{-# OPAQUE worker #-}
worker :: Int# -> Int# -> State# RealWorld -> (# State# RealWorld, () #)
worker initial failAfterEffect s0 =
  case newMutVar# (I# initial) s0 of { (# s1, ref #) ->
  case readMutVar# ref s1 of { (# s2, before #) ->
  case writeMutVar# ref (I# (initial +# 17#)) s2 of { s3 ->
  case readMutVar# ref s3 of { (# s4, after #) ->
  case before of { I# old -> case after of { I# new ->
    case old ==# initial of
      0# -> raise# WrongEffect
      _ -> case new ==# (initial +# 17#) of
        0# -> raise# WrongEffect
        _ -> case failAfterEffect of
          0# -> (# s4, () #)
          _ -> raise# ExpectedFailure
  } } } } } }

goodMain :: IO ()
goodMain = IO (worker 41# 0#)

badMain :: IO ()
badMain = IO (worker 41# 1#)
