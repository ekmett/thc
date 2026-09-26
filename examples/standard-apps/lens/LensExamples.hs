{-# LANGUAGE TemplateHaskell #-}
{-# LANGUAGE RankNTypes #-}
module Main (main) where

import Control.Lens
import Control.Monad (unless)
import Control.Monad.State.Strict (runState)
import qualified Data.Map.Strict as Map

data Portfolio = Portfolio
  { _owner :: String
  , _positions :: [Either String (Int, Int)]
  } deriving (Eq, Show)

makeLenses ''Portfolio

quantities :: Traversal' Portfolio Int
quantities = positions . traversed . _Right . _2

-- Keep the input-dependent applications in exported Core. These exercise the
-- real lens implementation; expected answers below use ordinary data values.
{-# OPAQUE checks #-}
checks :: Int -> [(String, Bool)]
checks n =
  let original = Portfolio "Ada" [Right (n, 2), Left "pending", Right (3, 4)]
      changed = over quantities (+ 10) original
      stateAction = do
        old <- preuse (positions . ix 0 . _Right . _2)
        positions . ix 0 . _Right . _2 += 5
        owner .= "Grace"
        quantities *= 2
        total <- uses positions (sumOf (folded . _Right . _2))
        pure (old, total)
      (answer, finalState) = runState stateAction original
      table = Map.fromList [("alpha", n), ("beta", 7)]
  in
    [ ("nested traversal", changed == Portfolio "Ada" [Right (n, 12), Left "pending", Right (3, 14)])
    , ("fold composition", sumOf (positions . folded . _Right . to (uncurry (*))) original == 2 * n + 12)
    , ("matching prism", preview (positions . ix 2 . _Right . _1) original == Just 3)
    , ("nonmatching prism", preview (positions . ix 1 . _Right) original == Nothing)
    , ("prism construction", (review (_Just . _Right) n :: Maybe (Either String Int)) == Just (Right n))
    , ("state result", answer == (Just 2, 22))
    , ("state updates", finalState == Portfolio "Grace" [Right (n, 14), Left "pending", Right (3, 8)])
    , ("indexed traversal", itoListOf (itraversed . _Right) (_positions original) == [(0, (n, 2)), (2, (3, 4))])
    , ("parts replacement", set (partsOf quantities) [8, 9] original == Portfolio "Ada" [Right (n, 8), Left "pending", Right (3, 9)])
    , ("filtered traversal", over (quantities . filtered even) negate original == Portfolio "Ada" [Right (n, -2), Left "pending", Right (3, -4)])
    , ("map insertion", set (at "gamma") (Just 11) table == Map.fromList [("alpha", n), ("beta", 7), ("gamma", 11)])
    , ("missing map focus", over (ix "absent") (+ 1) table == table)
    , ("type-changing traversal", over (traversed . _Right . _1) show (_positions original) == [Right (show n, 2), Left "pending", Right ("3", 4)])
    , ("empty traversal", toListOf (positions . traversed . _Right) (Portfolio "empty" []) == [])
    ]

main :: IO ()
main = do
  let results = checks 42
      failed = [name | (name, passed) <- results, not passed]
  unless (null failed) (fail ("lens public API failures: " ++ show failed))
  putStrLn ("lens-public-api: " ++ show (length results) ++ " checks passed")
