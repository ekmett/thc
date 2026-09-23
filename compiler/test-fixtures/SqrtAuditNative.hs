{-# LANGUAGE MagicHash #-}
module Main where
import SqrtAudit
import GHC.Exts
import GHC.Float (castFloatToWord32, castWord32ToFloat, castDoubleToWord64, castWord64ToDouble)
import System.Environment (getArgs)

main :: IO ()
main = do
  [path] <- getArgs
  contents <- readFile path
  mapM_ run (map words (lines contents))
  where
    run ["sqrtFloat", bits] = case castWord32ToFloat (read bits) of
      F# x -> putStrLn ("sqrtFloat\t" ++ bits ++ "\t" ++ show (castFloatToWord32 (F# (sqrtFloat x))))
    run ["sqrtDouble", bits] = case castWord64ToDouble (read bits) of
      D# x -> putStrLn ("sqrtDouble\t" ++ bits ++ "\t" ++ show (castDoubleToWord64 (D# (sqrtDouble x))))
    run ["floatCase", value] = case read value of I# x -> putStrLn ("floatCase\t" ++ value ++ "\t" ++ show (I# (floatCase x)))
    run ["doubleCase", value] = case read value of I# x -> putStrLn ("doubleCase\t" ++ value ++ "\t" ++ show (I# (doubleCase x)))
    run row = error (show row)
