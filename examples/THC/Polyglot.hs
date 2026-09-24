-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE GHCForeignImportPrim, MagicHash, UnboxedTuples, UnliftedFFITypes #-}
-- | A GHC-typed module boundary for THC's versioned Truffle polyglot calls.
-- The foreign symbols are supplied by THC, not by a native C library.
module THC.Polyglot (Value, eval, evalJS, readMember, executeInt) where

import GHC.Exts (Addr#, Any, Int#, RealWorld, State#)
import GHC.IO (IO(..))
import GHC.Types (Int(..))

-- | An opaque, context-owned guest value. It is a managed reference, never an
-- Addr# masquerading as a native pointer or an integer handle.
newtype Value = Value Any

foreign import prim "thc_polyglot_v1_eval"
  eval# :: Addr# -> Addr# -> Addr# -> State# RealWorld -> (# State# RealWorld, Any #)
foreign import prim "thc_polyglot_v1_read_member"
  readMember# :: Any -> Addr# -> State# RealWorld -> (# State# RealWorld, Any #)
foreign import prim "thc_polyglot_v1_execute_int"
  executeInt# :: Any -> Int# -> State# RealWorld -> (# State# RealWorld, Int# #)

-- | Evaluate source in a language installed in the enclosing Polyglot Context.
-- Arguments are NUL-terminated UTF-8 Addr# literals for language, source,
-- and source name. A later API can add dynamically allocated UTF-8 buffers.
eval :: Addr# -> Addr# -> Addr# -> IO Value
eval language source sourceName = IO $ \state ->
  case eval# language source sourceName state of
    (# next, raw #) -> (# next, Value raw #)

evalJS :: Addr# -> Addr# -> IO Value
evalJS source sourceName = eval "js"# source sourceName

readMember :: Value -> Addr# -> IO Value
readMember (Value receiver) key = IO $ \state ->
  case readMember# receiver key state of
    (# next, raw #) -> (# next, Value raw #)

executeInt :: Value -> Int -> IO Int
executeInt (Value function) (I# input) = IO $ \state ->
  case executeInt# function input state of
    (# next, result #) -> (# next, I# result #)
