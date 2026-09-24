{-# LANGUAGE MagicHash #-}
module THC.PolyglotDemo (main) where

import THC.Polyglot

-- The returned Int crosses JS -> Haskell -> JS. The second JS call prints
-- only after checking the exact value, and the Haskell branch checks it too.
main :: IO ()
main = do
  object <- evalJS "({ add: x => x + 7, check: x => { if (x !== 42) throw new Error('expected 42'); console.log('THC polyglot result: ' + x); return x; } })"# "polyglot-demo.js"#
  add <- readMember object "add"#
  answer <- executeInt add 35
  check <- readMember object "check"#
  checked <- executeInt check answer
  if checked == 42 then pure () else do
    _ <- evalJS "throw new Error('Haskell observed a non-42 result')"# "polyglot-check.js"#
    pure ()
