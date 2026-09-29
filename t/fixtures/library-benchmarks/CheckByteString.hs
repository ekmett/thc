{-# LANGUAGE MagicHash #-}
module Main (main) where
import Control.Monad (forM_, unless)
import GHC.Exts (Int(I#))
import qualified ByteStringBenchmarks as B
main :: IO ()
main = forM_ [0..15] $ \n -> case n of
  I# input -> unless (I# (B.bytestringReadInt input) == -128 - 256*n)
    (error ("readInt oracle mismatch for corpus " ++ show n))
