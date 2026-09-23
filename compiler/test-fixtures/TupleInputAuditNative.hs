{-# LANGUAGE MagicHash #-}
module Main where
import GHC.Exts
import TupleInputAudit
import System.Environment (getArgs)

inputs :: [Int]
inputs = [minBound, -3000000000, -4097, -1, 0, 1, 7, 4097, 3000000000, maxBound-1, maxBound]
functions :: [(String, Int# -> Int#)]
functions = [("pairCase",pairCase),("mixedCase",mixedCase),("indirectCase",indirectCase),
 ("prefixCase",prefixCase),("papCase",papCase),("overCase",overCase),("lazyCase",lazyCase),
 ("roundTripCase",roundTripCase),("selfCase",selfCase),("stateCase",stateCase),
 ("nestedCase",nestedCase),("deadCase",deadCase)]
row :: String -> (Int# -> Int#) -> Int -> IO ()
row name f x@(I# n) = putStrLn (name ++ "\t" ++ show x ++ "\t" ++ show (I# (f n)))
main :: IO ()
main = do
 args <- getArgs
 if args == ["--pairs"] then
  mapM_ (\(x@(I# a), y@(I# b)) -> putStrLn ("pairInputs\t" ++ show x ++ "\t" ++ show y ++ "\t" ++ show (I# (pairInputs a b))))
   [(minBound,maxBound),(maxBound,minBound),(0,0),(0,1),(1,0),(-7,19),(4097,-3000000000)]
 else do
  mapM_ (\(name,f) -> mapM_ (row name f) inputs) functions
  mapM_ (row "effectCase" effectCase) [0,1,4097,maxBound]
  mapM_ (row "selfDepth" selfDepth) [0,1,20001]
