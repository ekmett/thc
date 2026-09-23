{-# LANGUAGE MagicHash #-}
module Main where
import GHC.Exts
import SimdFloatX4

main :: IO ()
main = do
  mapM_ runFinite [(name, f, a, b, finite !! ((i+3*j) `mod` size), finite !! ((3*i+j+1) `mod` size))
    | (name, f) <- [("plusCase", plusCase), ("minusCase", minusCase), ("timesCase", timesCase)]
    , (i,a) <- zip [0..] finite, (j,b) <- zip [0..] finite]
  mapM_ runEdge [(name, f, a, (a+1) `mod` 21, (a+7) `mod` 21, (a+13) `mod` 21, b)
    | (name, f) <- [("edgePlus", edgePlus), ("edgeMinus", edgeMinus), ("edgeTimes", edgeTimes)]
    , a <- [0..20], b <- [0..20]]
  mapM_ (\n@(I# x) -> row "nonFmaCase" [n] (nonFmaCase x)) [-2,-1,0,1,2,3]
  where
    finite = [-33554435,-16777219,-16777217,-16777216,-16777215,-3,-2,-1,0,1,2,3,16777215,16777216,16777217,16777219,33554435] :: [Int]
    size = length finite
    row name xs answer = putStrLn (name ++ concatMap (("\t" ++) . show) xs ++ "\t" ++ show (I# answer))
    runFinite (name,f,a@(I# w),b@(I# x),c@(I# y),d@(I# z)) = row name [a,b,c,d] (f w x y z)
    runEdge (name,f,a@(I# v),b@(I# w),c@(I# x),d@(I# y),e@(I# z)) = row name [a,b,c,d,e] (f v w x y z)
