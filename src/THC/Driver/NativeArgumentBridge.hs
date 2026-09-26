-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
module THC.Driver.NativeArgumentBridge (nativeArgumentBridge) where

import Control.Monad (guard)
import Data.Char (isSpace)
import Data.List (isPrefixOf, nub)

-- LLVM permits a direct call whose integer argument widths differ from the
-- definition. GHC's x86_64 ccall ABI uses the low bits of each integer slot,
-- whereas Sulong requires matching Java scalar carriers. Recognize only
-- ordinary C functions in verified LLVM and make those truncations explicit.
-- Return ABI, pointer types, varargs and non-C calling conventions never adapt.
-- Both signatures come from the actual linked compiler output, not symbol names.
nativeArgumentBridge :: String -> String -> String -> String -> Maybe (String,String,[String])
nativeArgumentBridge target symbol entry source = do
  guard (target == "x86_64-unknown-linux-gnu")
  (calleeLine,callee) <- definition symbol
  (callerLine,caller) <- definition entry
  -- Do not replace an inlined/header-rewritten body, instrumentation, or a
  -- compiler alias with a guessed call. The adapter must still be exactly its
  -- original one direct call and return, with no other observable work.
  let bodyLines = map (dropWhile isSpace) . takeWhile (/= "}") . drop 1 $
        dropWhile (/= callerLine) (lines source)
  case bodyLines of
    [callLine,returnLine] -> passthroughCall symbol caller callLine returnLine
    _ -> Nothing
  guard (result caller == result callee && length (parameters caller) == length (parameters callee))
  let pairs = zip (parameters caller) (parameters callee)
  guard (any (uncurry (/=) . both scalar) pairs)
  guard (all compatible pairs)
  let formals = comma [render parameter ++ " %a" ++ show index |
        (index,parameter) <- zip [0::Int ..] (parameters caller)]
      lowered = ["  %n" ++ show index ++ " = trunc " ++ scalar from ++ " %a" ++ show index ++ " to " ++ scalar to |
        (index,(from,to)) <- zip [0::Int ..] pairs, scalar from /= scalar to]
      actuals = comma [render to ++ " %" ++ (if scalar from == scalar to then "a" else "n") ++ show index |
        (index,(from,to)) <- zip [0::Int ..] pairs]
      returned = renderResult (result caller)
      declaration = "declare " ++ renderResult (result callee) ++ " @" ++ symbol ++ "(" ++
        comma (map render (parameters callee)) ++ ")"
      body = unlines (["define " ++ returned ++ " @" ++ entry ++ "(" ++ formals ++ ") {"] ++ lowered ++
        ["  " ++ (if scalar (result caller) == "void" then "" else "%r = ") ++ "call " ++
          returned ++ " @" ++ symbol ++ "(" ++ actuals ++ ")",
         if scalar (result caller) == "void" then "  ret void" else "  ret " ++ scalar (result caller) ++ " %r", "}"])
  pure (declaration,body,[callerLine,calleeLine])
  where
    definition name = case [line | line <- lines source, "define " `isPrefixOf` line,
      let (_,after) = break (== '@') line, ("@" ++ name ++ "(") `isPrefixOf` after] of
        [line] -> (,) line <$> parseDefinition line
        _ -> Nothing
    both f (a,b) = (f a,f b)
    compatible (from,to)
      | scalar from == scalar to = extension from == extension to
      | otherwise = scalar from `elem` ["i64","i32"] && scalar to `elem` ["i8","i16","i32"] &&
          width (scalar from) > width (scalar to)
    width "i64" = 64 :: Int
    width "i32" = 32
    width "i16" = 16
    width _ = 8

data Parameter = Parameter { scalar :: String, extension :: String } deriving Eq
data Definition = Definition { result :: Parameter, parameters :: [Parameter], argumentNames :: [String] }

passthroughCall :: String -> Definition -> String -> String -> Maybe ()
passthroughCall symbol caller callLine returnLine = do
  let (assigned,rest) = break (== "call") (words callLine)
  called <- case rest of "call":tokens -> pure (unwords tokens); _ -> Nothing
  let (before,after) = break (== '@') called
      prefix = words before
  guard (not (null prefix) && ("@" ++ symbol ++ "(") `isPrefixOf` after)
  returned <- scalarParameter (last prefix) (init prefix)
  guard (returned == result caller && all (`elem` ["ccc","noundef","noalias","nonnull","signext","zeroext"]) (init prefix))
  actuals <- splitParameters (drop (length symbol + 2) after) >>= mapM parseParameter
  guard (map fst actuals == parameters caller && map snd actuals == argumentNames caller)
  let assignment = case reverse assigned of
        "tail":tokens -> reverse tokens
        "notail":tokens -> reverse tokens
        _ -> assigned
  if scalar returned == "void" then guard (null assignment && words returnLine == ["ret","void"])
    else case assignment of
      [value,"="] -> guard ("%" `isPrefixOf` value && words returnLine == ["ret",scalar returned,value])
      _ -> Nothing

-- A bounded recognizer over already verified LLVM, not an LLVM parser. Missing
-- or unfamiliar shapes leave the original adapter untouched. In particular,
-- ABI-changing parameter attributes (byval, sret, inalloca, etc.) never match.
parseDefinition :: String -> Maybe Definition
parseDefinition line = do
  let (before,after) = break (== '@') line
      prefix = words before
  guard (not (null prefix))
  returned <- scalarParameter (last prefix) (init prefix)
  guard (all (`elem` ["define","dso_local","dso_preemptable","internal","private","external",
    "hidden","protected","default","weak","weak_odr","linkonce","linkonce_odr","available_externally",
    "ccc","noundef","noalias","nonnull","signext","zeroext"]) (init prefix))
  let arguments = drop 1 (dropWhile (/= '(') after)
  raw <- splitParameters arguments
  args <- mapM parseParameter raw
  guard (all ((/= "void") . scalar . fst) args)
  pure (Definition returned (map fst args) (map snd args))

parseParameter :: String -> Maybe (Parameter,String)
parseParameter source = case words source of
  ty:attributes -> do
    guard (not (null attributes) && "%" `isPrefixOf` last attributes)
    guard (all allowed (init attributes))
    parameter <- scalarParameter ty (init attributes)
    pure (parameter,last attributes)
  _ -> Nothing
  where
    allowed token = token `elem` ["noundef","noalias","nocapture","readonly","writeonly","readnone",
      "nonnull","returned","signext","zeroext","captures(none)"] ||
      any (\prefix -> prefix `isPrefixOf` token && last token == ')')
        ["dereferenceable(","dereferenceable_or_null("]

scalarParameter :: String -> [String] -> Maybe Parameter
scalarParameter ty attributes = do
  guard (ty `elem` ["void","i8","i16","i32","i64","ptr","float","double"])
  let extensions = nub (filter (`elem` ["signext","zeroext"]) attributes)
  guard (length extensions <= 1)
  pure (Parameter ty (unwords extensions))

render :: Parameter -> String
render parameter = scalar parameter ++ if null (extension parameter) then "" else " " ++ extension parameter

renderResult :: Parameter -> String
renderResult parameter = (if null (extension parameter) then "" else extension parameter ++ " ") ++ scalar parameter

comma :: [String] -> String
comma [] = ""
comma (value:rest) = value ++ concatMap (", " ++) rest

splitParameters :: String -> Maybe [String]
splitParameters = go (0::Int) [] []
  where
    go _ _ _ [] = Nothing
    go depth current previous (character:rest)
      | character == ')' && depth == 0 =
          Just (if null current && null previous then [] else reverse (reverse current:previous))
      | character == ',' && depth == 0 = go depth [] (reverse current:previous) rest
      | otherwise = go (depth + if character == '(' then 1 else if character == ')' then -1 else 0)
          (character:current) previous rest
