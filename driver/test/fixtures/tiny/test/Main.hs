module Main where

import Greeting (greeting)

main :: IO ()
main = if greeting `elem` ["hello Cabal", "HELLO Cabal"] then pure () else fail greeting
