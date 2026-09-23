{-# LANGUAGE MagicHash #-}
module Main where
import GHC.Exts
import StateTupleAudit
main :: IO ()
main = mapM_ run [(name, f, a) | (name, f) <- entries, a <- inputs]
  where
    entries = [("pairCase", pairCase), ("lazyCase", lazyCase), ("captureCase", captureCase)]
    inputs = [minBound, -4097, -1, 0, 1, 4097, maxBound] :: [Int]
    run (name, f, a@(I# x)) = putStrLn (name ++ "\t" ++ show a ++ "\t" ++ show (I# (f x)))
