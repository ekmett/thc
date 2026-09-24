{-# LANGUAGE MagicHash #-}
module Main where
import GHC.Exts (Int(I#))
import ShowWordListAudit
main :: IO ()
main = getContents >>= mapM_ (run . words) . lines
 where
  run [name, input, shape, index] = case (read input, read shape, read index) of
    (I# x, I# s, I# i) -> emit name (I# x) (I# s) (I# i) $ case name of
      "wordChecksum" -> I# (wordChecksum x)
      "wordCharacter" -> I# (wordCharacter x i)
      "listChecksum" -> I# (listChecksum x s)
      "listCharacter" -> I# (listCharacter x s i)
      _ -> error "unknown native Show Word/list entry"
  run _ = error "malformed native Show Word/list input"
  emit name input shape index result = putStrLn
    (name ++ "\t" ++ show input ++ "\t" ++ show shape ++ "\t" ++ show index ++ "\t" ++ show result)
