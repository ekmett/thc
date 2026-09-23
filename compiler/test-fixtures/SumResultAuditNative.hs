{-# LANGUAGE MagicHash #-}
module Main where
import GHC.Exts
import qualified Control.Exception as E
import SumResultAudit
import System.Environment (getArgs)
emit :: String -> (Int# -> Int#) -> Int -> IO ()
emit n f x@(I# a) = do
  value <- E.try (E.evaluate (I# (f a))) :: IO (Either E.SomeException Int)
  putStrLn (n ++ "\t" ++ show x ++ "\t" ++ either (const "throws") show value)
main :: IO ()
main = do
  arguments <- getArgs
  if arguments == ["--pairs"] then sequence_ [let I# a = x; I# b = y in
      putStrLn ("pairedInputs\t" ++ show x ++ "\t" ++ show y ++ "\t" ++ show (I# (pairedInputs a b)))
      | (x,y) <- [(minBound,maxBound),(maxBound,minBound),(-7,11),(11,-7),(0,0),(17,17),(3000000000,-3000000000)]]
  else sequence_ [emit n f x | (n,f) <- [("forwardCase",forwardCase),("outstandingCase",outstandingCase),
    ("pairedCase",pairedCase),("lazyLeafCase",lazyLeafCase),("mixedCase",mixedCase),("singletonBoxCase",singletonBoxCase),
    ("selfCase",selfCase),("mutualCase",mutualCase),("effectStateCase",effectStateCase),("effectEmptyCase",effectEmptyCase),("throwCase",throwCase)],
    x <- [minBound,-4097,-7,-3,-1,0,1,7,4097,maxBound]]
