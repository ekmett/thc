{-# LANGUAGE ForeignFunctionInterface #-}
module THC.JavaScriptDemo (main) where

-- Each import names a JavaScript function value. THC applies the Haskell
-- arguments to it when the IO action runs in the enclosing Polyglot Context.
foreign import javascript "(x) => x + 7"
  jsAddSeven :: Int -> IO Int

foreign import javascript "(x, y) => x + y"
  jsAdd :: Int -> Int -> IO Int

foreign import javascript "(x) => x * 0.5"
  jsHalf :: Double -> IO Double

foreign import javascript "() => 42"
  jsAnswer :: IO Int

foreign import javascript "(x) => { if (x !== 42) throw new Error('THC JavaScript FFI expected 42'); console.log('THC polyglot result: ' + x); }"
  jsReport :: Int -> IO ()

main :: IO ()
main = do
  unary <- jsAddSeven 35
  binary <- jsAdd 19 23
  floating <- jsHalf 84.0
  zero <- jsAnswer
  if unary == 42 && binary == 42 && floating == 42.0 && zero == 42
    then jsReport unary
    else jsReport (-1)
