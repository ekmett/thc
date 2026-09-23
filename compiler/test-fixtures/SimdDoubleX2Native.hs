{-# LANGUAGE MagicHash #-}
module Main where
import GHC.Exts
import GHC.Float (castDoubleToWord64)
import SimdDoubleX2

main :: IO ()
main = do
  mapM_ (\(name,f) -> mapM_ (\(a@(I# x),b@(I# y)) -> row name [a,b] (show (I# (f x y))))
    [(a,b) | a <- finite, b <- finite]) [("plusCase",plusCase),("minusCase",minusCase),("timesCase",timesCase)]
  mapM_ (\(name,f) -> mapM_ (\(a@(I# x),b@(I# y),c@(I# z)) -> row name [a,b,c] (bits (f x y z)))
    [(a,(a+7) `mod` 21,c) | a <- [0..20],c <- [0..20]])
    [("edgePlus0",edgePlus0),("edgePlus1",edgePlus1),("edgeMinus0",edgeMinus0),
     ("edgeMinus1",edgeMinus1),("edgeTimes0",edgeTimes0),("edgeTimes1",edgeTimes1)]
  mapM_ (\(name,f) -> mapM_ (\(a@(I# x),b@(I# y)) -> row name [a,b] (bits (f x y)))
    [(a,(a+7) `mod` 21) | a <- [0..20]]) [("move0",move0),("move1",move1)]
  mapM_ (\a@(I# x) -> row "broadcastCase" [a] (bits (broadcastCase x))) [0..20]
  mapM_ (\a@(I# x) -> row "nonFmaCase" [a] (bits (nonFmaCase x))) [-2,-1,0,1,2,3]
  where
    finite = [-18014398509481987,-9007199254740995,-9007199254740993,-9007199254740992,
              -9007199254740991,-3,-1,0,1,3,9007199254740991,9007199254740992,
              9007199254740993,9007199254740995,18014398509481987] :: [Int]
    row name xs result = putStrLn (name ++ concatMap (("\t" ++) . show) xs ++ "\t" ++ result)
    bits x = if isNaN (D# x) then "nan" else show (castDoubleToWord64 (D# x))
