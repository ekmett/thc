-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples #-}
{-# OPTIONS_GHC -fno-do-lambda-eta-expansion #-}
module ThreadScheduling where

import GHC.Exts
import GHC.Internal.Prim.Ext (getThreadAllocationCounter#)

data Box = Box Int#

{-# OPAQUE emptySpark #-}
emptySpark :: Int# -> Int#
emptySpark token = runRW# (\s -> case numSparks# s of { (# s1, count #) ->
  case (getSpark# s1 :: (# State# RealWorld, Int#, Bool #)) of
    (# _, flag, value #) -> case flag of
      0# -> case value of { False -> token +# (count ==# 0#); True -> -1# }
      _ -> -2# })

{-# OPAQUE lazyPar #-}
lazyPar :: Int# -> Int#
lazyPar token = token +# par# (raise# (Box 99#) :: Box)

{-# OPAQUE lazySpark #-}
lazySpark :: Int# -> Int#
lazySpark token = runRW# (\s -> case spark# (raise# (Box token) :: Box) s of
  { (# _, _ #) -> token +# 1# })

{-# OPAQUE sparkValue #-}
sparkValue :: Int# -> Int#
sparkValue token = runRW# (\s -> case spark# (Box token) s of
  { (# _, retained #) -> case retained of { Box value -> value +# 1# } })

{-# OPAQUE timedDelay #-}
timedDelay :: Int# -> Int#
timedDelay microseconds = runRW# (\s -> case delay# microseconds s of _ -> microseconds)

{-# OPAQUE currentCounter #-}
currentCounter :: Int# -> Int#
currentCounter size = runRW# (\s ->
  case setThreadAllocationCounter# (intToInt64# 1000000000#) s of { s1 ->
  case newByteArray# size s1 of { (# s2, bytes #) ->
  case writeWord8Array# bytes 0# (wordToWord8# 7##) s2 of { s3 ->
  case getThreadAllocationCounter# s3 of { (# _, remaining #) ->
    case int64ToInt# remaining of { n ->
      (n <=# (1000000000# -# size)) `andI#` (n ># 900000000#)
  } } } } })

{-# OPAQUE negativeCounter #-}
negativeCounter :: Int# -> Int#
negativeCounter token = runRW# (\s ->
  case setThreadAllocationCounter# (intToInt64# (-17#)) s of { s1 ->
  case getThreadAllocationCounter# s1 of { (# _, remaining #) ->
    token +# (int64ToInt# remaining <=# -17#) } })

{-# OPAQUE pinnedFork #-}
pinnedFork :: Int# -> Int#
pinnedFork token = runRW# (\s ->
  case newMVar# s of { (# s1, ready #) ->
  case newMVar# s1 of { (# s2, gate #) ->
  case newMVar# s2 of { (# s3, done #) ->
  case forkOn# 0# (\s4 -> case myThreadId# s4 of { (# s5, self #) ->
    case threadStatus# self s5 of { (# s6, _, capability, locked #) ->
    case putMVar# ready (Box (10# *# capability +# locked)) s6 of { s7 ->
    case takeMVar# gate s7 of { (# s8, _ #) ->
    case putMVar# done () s8 of { s9 -> (# s9, raise# (Box 99#) #) }
    } } } }) s3 of { (# s4, child #) ->
  case takeMVar# ready s4 of { (# s5, Box observed #) ->
  case threadStatus# child s5 of { (# s6, _, capability, locked #) ->
  case putMVar# gate () s6 of { s7 ->
  case takeMVar# done s7 of { (# _, _ #) ->
    token +# observed +# 100# *# capability +# 10# *# locked
  } } } } } } } })

{-# OPAQUE otherCounter #-}
otherCounter :: Int# -> Int#
otherCounter token = runRW# (\s ->
  case newMVar# s of { (# s1, ready #) ->
  case newMVar# s1 of { (# s2, gate #) ->
  case newMVar# s2 of { (# s3, done #) ->
  case forkOn# 0# (\s4 -> case putMVar# ready () s4 of { s5 ->
    case takeMVar# gate s5 of { (# s6, _ #) ->
    case getThreadAllocationCounter# s6 of { (# s7, remaining #) ->
    case int64ToInt# remaining of { n ->
    -- GHC's other-thread setter omits the nursery adjustment; its documented
    -- accounting granularity is about 4 KiB (including a possible +8-byte start).
    case putMVar# done (Box ((n <=# 1000004096#) `andI#` (n ># 900000000#))) s7 of
      s8 -> (# s8, () #)
    } } } }) s3 of { (# s4, child #) ->
  case takeMVar# ready s4 of { (# s5, _ #) ->
  case setThreadAllocationCounter# (intToInt64# 700000000#) s5 of { s6 ->
  case setOtherThreadAllocationCounter# (intToInt64# 1000000000#) child s6 of { s7 ->
  case putMVar# gate () s7 of { s8 ->
  case takeMVar# done s8 of { (# s9, Box result #) ->
  case getThreadAllocationCounter# s9 of { (# _, remaining #) ->
    case int64ToInt# remaining of { n -> token +# result +#
      10# *# ((n <=# 700000000#) `andI#` (n ># 600000000#))
  } } } } } } } } } } })
