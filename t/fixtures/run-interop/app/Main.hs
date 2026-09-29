-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE ForeignFunctionInterface #-}
module Main (main) where
import Foreign.C.Types (CInt(..))
import InteropApi (snapshot)
import InteropSupport (support)
foreign import javascript "() => 7" answer :: IO Int
foreign import ccall unsafe "stdlib.h abs" absolute :: CInt -> IO CInt
main :: IO ()
main = do
  result <- answer
  native <- absolute (fromIntegral (negate support))
  if result == support && fromIntegral native == result
    then snapshot else fail "unexpected foreign answer"
