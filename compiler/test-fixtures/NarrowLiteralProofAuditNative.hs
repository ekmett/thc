{-# LANGUAGE MagicHash #-}
module Main where
import GHC.Exts
import NarrowLiteralProofAudit
main :: IO ()
main = mapM_ row [(name, x, f x) | (name,f) <- functions, x <- [minBound,-1,0,1,2,3,4,7,maxBound]]
  where
    row (name,x,value) = putStrLn (name ++ "\t" ++ show x ++ "\t" ++ show value)
    functions :: [(String, Int -> Int)]
    functions = [("writeInt8Literal", \(I# x) -> I# (writeInt8Literal x)),
                 ("writeWord8Literal", \(I# x) -> I# (writeWord8Literal x)),
                 ("writeInt16Literal", \(I# x) -> I# (writeInt16Literal x)),
                 ("writeWord16Literal", \(I# x) -> I# (writeWord16Literal x)),
                 ("writeInt32Literal", \(I# x) -> I# (writeInt32Literal x)),
                 ("writeWord32Literal", \(I# x) -> I# (writeWord32Literal x))]
