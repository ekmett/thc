{-# LANGUAGE MagicHash #-}
module Main where
import GHC.Exts
import SimdInt32X4
main :: IO ()
main = mapM_ run [(name, f, a, b, inputs !! ((i+3*j) `mod` 9), inputs !! ((3*i+j+1) `mod` 9))
  | (name, f) <- entries, (i,a) <- zip [0..] inputs, (j,b) <- zip [0..] inputs]
  where
    entries = [("vectorCase", vectorCase), ("subtractCase", subtractCase), ("branchCase", branchCase)]
    inputs = [minBound, -2147483649, -2147483648, -1, 0, 1, 2147483647, 2147483648, maxBound] :: [Int]
    run (name, f, a@(I# w), b@(I# x), c@(I# y), d@(I# z)) = putStrLn
      (name ++ "\t" ++ show a ++ "\t" ++ show b ++ "\t" ++ show c ++ "\t" ++ show d ++ "\t" ++ show (I# (f w x y z)))
