-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE GADTs, MagicHash #-}
module InterfaceLibrary (opaqueEntry, inlineEntry, recursiveEntry, wrapperEntry, Token(..)) where
import GHC.Exts

data Token a where
  Token :: {-# UNPACK #-} !Int -> Token Int

{-# OPAQUE makeToken #-}
makeToken :: (Int -> Token Int) -> Int -> Token Int
makeToken constructor value = constructor value

{-# OPAQUE wrapperEntry #-}
wrapperEntry :: Int# -> Int#
wrapperEntry n = case makeToken Token (I# n) of Token (I# value) -> value

{-# OPAQUE opaqueEntry #-}
opaqueEntry :: Int# -> Int#
opaqueEntry n = privateWorker (n +# 1#) -# 7#

{-# OPAQUE privateWorker #-}
privateWorker :: Int# -> Int#
privateWorker n = n *# 7# +# 11#

{-# INLINE inlineEntry #-}
inlineEntry :: Int# -> Int#
inlineEntry n = n +# 3#

{-# OPAQUE recursiveEntry #-}
recursiveEntry :: Int# -> Int#
recursiveEntry n = case n <=# 0# of
  1# -> 11#
  _ -> 7# +# recursiveEntry (n -# 1#)
