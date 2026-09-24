-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

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
    floatMath =
      [("fabsFloat", fabsFloat), ("expFloat", expFloat), ("expm1Float", expm1Float)
      ,("logFloat", logFloat), ("log1pFloat", log1pFloat), ("sinFloat", sinFloat)
      ,("cosFloat", cosFloat), ("tanFloat", tanFloat)
      ,("asinFloat", asinFloat), ("acosFloat", acosFloat), ("atanFloat", atanFloat)
      ,("sinhFloat", sinhFloat), ("coshFloat", coshFloat), ("tanhFloat", tanhFloat)
      ,("powerFloat", powerFloat)]
    doubleMath =
      [("fabsDouble", fabsDouble), ("expDouble", expDouble), ("expm1Double", expm1Double)
      ,("logDouble", logDouble), ("log1pDouble", log1pDouble), ("sinDouble", sinDouble)
      ,("cosDouble", cosDouble), ("tanDouble", tanDouble)
      ,("asinDouble", asinDouble), ("acosDouble", acosDouble), ("atanDouble", atanDouble)
      ,("sinhDouble", sinhDouble), ("coshDouble", coshDouble), ("tanhDouble", tanhDouble)
      ,("powerDouble", powerDouble)]
    run ["sqrtFloat", bits] = case castWord32ToFloat (read bits) of
      F# x -> putStrLn ("sqrtFloat\t" ++ bits ++ "\t" ++ show (castFloatToWord32 (F# (sqrtFloat x))))
    run ["sqrtDouble", bits] = case castWord64ToDouble (read bits) of
      D# x -> putStrLn ("sqrtDouble\t" ++ bits ++ "\t" ++ show (castDoubleToWord64 (D# (sqrtDouble x))))
    run ["floatCase", value] = case read value of I# x -> putStrLn ("floatCase\t" ++ value ++ "\t" ++ show (I# (floatCase x)))
    run ["doubleCase", value] = case read value of I# x -> putStrLn ("doubleCase\t" ++ value ++ "\t" ++ show (I# (doubleCase x)))
    run [name, bits] | Just operation <- lookup name floatMath = case castWord32ToFloat (read bits) of
      F# x -> putStrLn (name ++ "\t" ++ bits ++ "\t" ++ show (castFloatToWord32 (F# (operation x))))
    run [name, bits] | Just operation <- lookup name doubleMath = case castWord64ToDouble (read bits) of
      D# x -> putStrLn (name ++ "\t" ++ bits ++ "\t" ++ show (castDoubleToWord64 (D# (operation x))))
    run row = error (show row)
