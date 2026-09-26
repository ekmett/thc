-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
module Main (main) where

import qualified Numeric.AD.Mode.Kahn as K
import qualified Numeric.AD.Mode.Kahn.Double as KD

shared :: Num a => Int -> a -> a
shared 0 x = x
shared n x = let y = shared (n - 1) x in y + y

main :: IO ()
main = do
  check "kahn-diff" $ K.diff (\x -> x * x + x) (1 :: Double) == 3
  check "kahn-grad" $ K.grad (\[x,y] -> x * x + y) [1,2 :: Double] == [2,1]
  check "kahn-jacobian" $ K.jacobian (\[x,y] -> [x + y, x * y]) [1,2 :: Double] == [[1,1],[2,1]]
  check "kahn-hessian" $ K.hessian (\[x,y] -> x * y) [1,2 :: Double] == [[0,1],[1,0]]
  check "kahn-double" $ KD.diff (\x -> x * x + x) 1 == 3
  mapM_ (\depth -> check ("shared-" ++ show depth) $
    K.diff (shared depth) (3 :: Double) == fromInteger (2 ^ depth)) [0,1,8,20,32]
  where
    check label valid = if valid then putStrLn (label ++ ": ok") else fail (label ++ ": mismatch")
