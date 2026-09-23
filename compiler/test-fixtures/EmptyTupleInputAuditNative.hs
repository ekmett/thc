{-# LANGUAGE MagicHash #-}
module Main where
import GHC.Exts
import EmptyTupleInputAudit
import System.Environment (getArgs)
main :: IO ()
main = do
  args <- getArgs
  if args == ["--pairs"] then mapM_ runPair [(minBound,maxBound),(maxBound,minBound),(0,0),(0,1),(1,0),(-7,19),(4097,-3000000000)]
  else ordinaryRows
  where
    runPair (x@(I# a), y@(I# b)) = putStrLn ("betweenInputs\t" ++ show x ++ "\t" ++ show y ++ "\t" ++ show (I# (betweenInputs a b)))

ordinaryRows :: IO ()
ordinaryRows = do
  mapM_ run [(name, f, x) | (name, f) <- ordinary, x <- inputs]
  mapM_ run [(name, f, x) | (name, f) <- [("selfDepth", selfDepth), ("mutualDepth", mutualDepth)], x <- [0,1,20001]]
  mapM_ run [(name, f, x) | (name, f) <- [("effectCase", effectCase), ("effectPapCase", effectPapCase)], x <- [0,1,4097,maxBound]]
  where
    ordinary = [("scalarControl",scalarControl),("beforeCase",beforeCase),("betweenCase",betweenCase),("afterCase",afterCase),
      ("usedCase",usedCase),("papEmptyCase",papEmptyCase),("papTwoEmptyCase",papTwoEmptyCase),
      ("papMixedCase",papMixedCase),("overCase",overCase),("lazyCase",lazyCase),("pairCase",pairCase),
      ("selfCase",selfCase),("mutualCase",mutualCase)]
    inputs = [minBound,-4097,-1,0,1,4097,3000000000,maxBound] :: [Int]
    run (name, f, x@(I# raw)) = putStrLn (name ++ "\t" ++ show x ++ "\t" ++ show (I# (f raw)))
