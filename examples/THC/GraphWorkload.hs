-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE BangPatterns, MagicHash #-}
module THC.GraphWorkload
  ( graphChecksum, graphReachable, graphDistanceTotal, graphDistanceAt, graphControl
  , generatedGraph, breadthFirst, controlGraph
  ) where

import Data.Bits ((.&.))
import Data.List (foldl')
import qualified Data.IntMap.Strict as IntMap
import qualified Data.IntSet as IntSet
import qualified Data.Sequence as Seq
import GHC.Exts (Int(I#), Int#)

type Graph = IntMap.IntMap [Int]

boundedSize :: Int -> Int
boundedSize = max 0 . min 512

-- Mixed-sign vertex keys model a small dependency graph. The last quarter is
-- disconnected from vertex zero. Each component has a ring, forward shortcuts,
-- duplicate edges and self-loops; none of these should enqueue a vertex twice.
generatedGraph :: Int -> Graph
generatedGraph input = IntMap.fromList [(key i, neighbours i) | i <- [0 .. n - 1]]
  where
    n = boundedSize input
    cut = n - n `quot` 4
    key i = 2 * i - n
    neighbours i = map key [next, jump, i, next]
      where
        base = if i < cut then 0 else cut
        end = if i < cut then cut else n
        next = if i + 1 < end then i + 1 else base
        step = 1 + ((37 * i + 11) .&. 7)
        jump = if i + step < end then i + step else base

-- Mark at enqueue, not dequeue: diamonds and duplicate edges retain shortest
-- distances without duplicate pending work. A dangling edge denotes a sink;
-- an absent starting vertex denotes an empty search.
breadthFirst :: Graph -> Int -> [(Int, Int)]
breadthFirst graph start
  | not (IntMap.member start graph) = []
  | otherwise = go (Seq.singleton (start, 0)) (IntSet.singleton start) []
  where
    go queue seen found = case Seq.viewl queue of
      Seq.EmptyL -> reverse found
      (vertex, distance) Seq.:< rest ->
        let add (!pending, !visited) neighbour
              | IntSet.member neighbour visited = (pending, visited)
              | otherwise = (pending Seq.|> (neighbour, distance + 1), IntSet.insert neighbour visited)
            (!next, !visited) = foldl' add (rest, seen) (IntMap.findWithDefault [] vertex graph)
        in go next visited ((vertex, distance) : found)

summary :: [(Int, Int)] -> (Int, Int, Int)
summary = foldl' step (0, 0, 0)
  where
    step (!count, !total, !checksum) (vertex, distance) =
      (count + 1, total + distance,
       (checksum + (vertex .&. 65535) * 257 + (distance + 1) * (1 + (vertex .&. 1023))) .&. 2147483647)

observations :: Int -> (Int, Int, Int)
observations input = summary (breadthFirst (generatedGraph input) (negate (boundedSize input)))

graphReachable :: Int# -> Int#
graphReachable raw = case observations (I# raw) of (I# count, _, _) -> count

graphDistanceTotal :: Int# -> Int#
graphDistanceTotal raw = case observations (I# raw) of (_, I# total, _) -> total

-- The primary workload entry; this result depends on both the reached vertex
-- set and every shortest distance, independent of adjacency-list ordering.
graphChecksum :: Int# -> Int#
graphChecksum raw = case observations (I# raw) of
  (count, total, checksum) ->
    case (checksum + 17 * count + 31 * total) .&. 2147483647 of I# result -> result

-- A unary diagnostic entry packs size*1024 + vertexIndex. Inputs used by the
-- oracle are nonnegative and bounded; absent/unreachable vertices return -1.
graphDistanceAt :: Int# -> Int#
graphDistanceAt raw = case result of I# value -> value
  where
    code = max 0 (I# raw)
    n = boundedSize (code `quot` 1024)
    vertex = 2 * (code .&. 1023) - n
    result = maybe (-1) id (lookup vertex (breadthFirst (generatedGraph n) (negate n)))

-- Small, explicit graph controls, separate from the deterministic generator.
controlGraph :: Int -> (Graph, Int)
controlGraph test = case test of
  0 -> (IntMap.empty, 0)
  1 -> (IntMap.singleton 0 [], 0)
  2 -> (IntMap.fromList [(0,[1]),(1,[2]),(2,[])], 0)
  3 -> (IntMap.fromList [(0,[1,2]),(1,[3]),(2,[3]),(3,[])], 0)
  4 -> (IntMap.fromList [(-3,[-3,7,7]),(7,[-3])], -3)
  5 -> (IntMap.fromList [(-1,[2]),(2,[]),(9,[10]),(10,[])], -1)
  6 -> (IntMap.singleton 0 [1], 0)
  7 -> (IntMap.singleton 0 [1], 99)
  8 -> (IntMap.fromList [(0,[1,3]),(1,[2]),(2,[3]),(3,[])], 0)
  _ -> (IntMap.fromList [(0,[1]),(1,[2,0]),(2,[0])], 2)

-- Pack test*32+field: 0=count, 1=distance total, 2=checksum; fields 3..19
-- query vertices -3..13 individually, including missing and unreachable keys.
graphControl :: Int# -> Int#
graphControl raw = case result of I# value -> value
  where
    code = max 0 (I# raw)
    (graph, start) = controlGraph (code `quot` 32)
    found = breadthFirst graph start
    (count, total, checksum) = summary found
    field = code .&. 31
    result = case field of
      0 -> count
      1 -> total
      2 -> (checksum + 17 * count + 31 * total) .&. 2147483647
      _ -> maybe (-1) id (lookup (field - 6) found)
