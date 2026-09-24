-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash, NoImplicitPrelude, UnboxedTuples #-}
module AddressFieldAudit where
import GHC.Exts

data Packet = Packet Addr# Int# Int
data Twin = Twin Addr# Addr# Int

bottom :: Int
bottom = bottom

-- Five source bytes plus GHC's implicit terminal NUL. The explicit NUL
-- separates two nonempty byte sequences; these are byte reads, not decoding.
{-# OPAQUE makePacket #-}
makePacket :: Int# -> Packet
makePacket x = Packet (plusAddr# "A\255\128\0B"# (andI# x 3#)) x bottom

{-# OPAQUE readPacket #-}
readPacket :: Packet -> Int# -> Int#
readPacket (Packet p x _) y = ord# (indexCharOffAddr# p 0#) +# (x *# 3#) +# y

{-# OPAQUE opaqueFunction #-}
opaqueFunction :: (Int# -> Int#) -> Int# -> Int#
opaqueFunction f x = f x

{-# OPAQUE finishPacket #-}
finishPacket :: (Int -> Packet) -> Int -> Packet
finishPacket f z = f z

{-# OPAQUE keepTwin #-}
keepTwin :: Twin -> Twin
keepTwin t = t

{-# OPAQUE keepPacket #-}
keepPacket :: Packet -> Packet
keepPacket p = p

natural :: Int# -> Int#
natural x = case Packet "A\255\128\0B"# x bottom of
  Packet p y _ -> ord# (indexCharOffAddr# p (andI# x 3#)) +# y

returned :: Int# -> Int#
returned x = readPacket (makePacket x) (x +# 2#)

captured :: Int# -> Int#
captured x = case makePacket x of
  Packet p y z -> opaqueFunction (\k -> readPacket (Packet p y z) k) (x +# 5#)

partial :: Int# -> Int#
partial x = readPacket (finishPacket (Packet (plusAddr# "A\255\128\0B"# (andI# x 3#)) x) bottom) (x +# 7#)

twins :: Int# -> Int#
twins x = case keepTwin (Twin (plusAddr# "A\255\128\0B"# (andI# x 3#)) "\0Z"# bottom) of
  Twin p q _ -> ord# (indexCharOffAddr# p 0#) +# (ord# (indexCharOffAddr# q 1#) *# 7#) +# x

emptyLiteral :: Int# -> Int#
emptyLiteral x = readPacket (keepPacket (Packet ""# x bottom)) (x +# 11#)

terminator :: Int# -> Int#
terminator x = readPacket (keepPacket (Packet (plusAddr# "A\255\128\0B"# 5#) x bottom)) (x +# 13#)

backwards :: Int# -> Int#
backwards x = case keepPacket (Packet (plusAddr# "A\255\128\0B"# 4#) x bottom) of
  Packet p y _ -> ord# (indexCharOffAddr# p ((andI# x 3#) -# 4#)) +# y

-- This remains an explicit aggregate ABI frontier despite AddrRep heap fields.
{-# OPAQUE addressTuple #-}
addressTuple :: Int# -> (# Addr#, Int# #)
addressTuple x = (# "A"#, x #)

tupleFrontier :: Int# -> Int#
tupleFrontier x = case addressTuple x of
  (# p, y #) -> ord# (indexCharOffAddr# p 0#) +# y
