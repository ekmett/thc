-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
module TinyMath (twice) where

-- | Double a value without changing its numeric type.
--
-- >>> twice (21 :: Int)
-- 42
-- >>> map twice [1, 2, 3 :: Int]
-- [2,4,6]
twice :: Num a => a -> a
twice value = value + value
