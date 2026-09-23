{-# LANGUAGE MagicHash #-}
module THC.TreeCoverage (treeFold, treeMapFold, treeSelective, treeShared) where

import Data.Bits ((.&.))
import GHC.Exts (Int(I#), Int#)

-- Three alternatives and lazy recursive fields; Tagged also has a strict Int.
data Tree = Leaf Int | Fork Tree Tree | Tagged !Int Tree

{-# NOINLINE buildTree #-}
buildTree :: Int -> Int -> Tree
buildTree depth seed
  | depth <= 0 = Leaf seed
  | seed .&. 3 == 0 = Tagged seed (buildTree (depth - 1) (seed + 1))
  | otherwise = Fork (buildTree (depth - 1) (seed + 3))
                     (buildTree (depth - 1) (seed - 5))

{-# NOINLINE foldTree #-}
foldTree :: Tree -> Int
foldTree tree = case tree of
  Leaf value -> value
  Fork left right -> foldTree left * 37 + foldTree right * 11 + 1
  Tagged tag child -> tag * 7 + foldTree child

mapTree :: (Int -> Int) -> Tree -> Tree
mapTree f tree = case tree of
  Leaf value -> Leaf (f value)
  Fork left right -> Fork (mapTree f left) (mapTree f right)
  Tagged tag child -> Tagged tag (mapTree f child)

{-# OPAQUE neverTree #-}
neverTree :: Tree
neverTree = neverTree

{-# OPAQUE selectiveTree #-}
selectiveTree :: Int -> Tree
selectiveTree seed
  | even seed = Tagged seed (Fork (Leaf seed) neverTree)
  | otherwise = Tagged seed (Fork neverTree (Leaf seed))

-- Only the chosen branch terminates; neither the wrapper nor its parent case
-- may force the other lazy field.
{-# OPAQUE selectTree #-}
selectTree :: Int -> Tree -> Int
selectTree direction tree = case tree of
  Leaf value -> value
  Tagged tag child -> tag + selectTree direction child
  Fork left right
    | even direction -> selectTree direction left
    | otherwise -> selectTree direction right

{-# OPAQUE sharedTreeProducer #-}
sharedTreeProducer :: Int -> Int -> Tree
sharedTreeProducer depth seed = buildTree depth seed

treeFold :: Int# -> Int#
treeFold raw = case foldTree (buildTree (n .&. 3) (n + 1)) of I# answer -> answer
  where n = I# raw

treeMapFold :: Int# -> Int#
treeMapFold raw = case foldTree
  (mapTree (\x -> x * 3 + n) (buildTree (n .&. 3) (n + 1))) of I# answer -> answer
  where n = I# raw

treeSelective :: Int# -> Int#
treeSelective raw = case selectTree n (selectiveTree n) of I# answer -> answer
  where n = I# raw

treeShared :: Int# -> Int#
treeShared raw = let n = I# raw; tree = sharedTreeProducer (n .&. 3) (n + 1)
  in case foldTree (Fork tree (Tagged (n + 1) tree)) of I# answer -> answer
