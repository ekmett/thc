{-# LANGUAGE MagicHash, TypeFamilies #-}
-- Valid GHC enums outside THC's deliberately concrete, non-family slice.
module TagToEnumFrontier where
import GHC.Exts

data Parameterized a = P0 | P1
{-# OPAQUE parameterized #-}
parameterized :: Int# -> Parameterized Int
parameterized n = tagToEnum# n

data family Family a
data instance Family Int = F0 | F1
{-# OPAQUE family #-}
family :: Int# -> Family Int
family n = tagToEnum# n
