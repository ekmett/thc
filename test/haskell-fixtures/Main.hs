-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE OverloadedStrings #-}

-- Native fixture production belongs to Haskell. The JVM tests own the
-- independent arithmetic models and compiled guest comparisons.
module Main (main) where

import AggregateFixtures (prepareAggregate)
import WordFloatingFixtures (prepareWordFloating)
import ContinuationFixtures (prepareCoreContinuation)
import OriginalStdioFixtures (prepareOriginalStdio)
import SmallArrayFixtures (prepareSmallArrays)
import StackFixtures (prepareOriginalStack)
import BoxedArrayExtensionsFixtures (prepareBoxedArrayExtensions)
import Control.Monad (forM, forM_, unless, when)
import Data.Aeson (Value (..), decodeStrict', object, (.=))
import qualified Data.Aeson.KeyMap as KeyMap
import Data.Bits ((.&.), (.|.), xor, shiftL, shiftR)
import qualified Data.ByteString as BS
import Data.List (isPrefixOf, isSuffixOf, sort)
import qualified Data.Map.Strict as Map
import qualified Data.Set as Set
import Data.String (fromString)
import Data.Word (Word8, Word16)
import Foreign.Marshal.Alloc (alloca)
import Foreign.Ptr (Ptr, castPtr)
import Foreign.Storable (peek, poke)
import FixtureSupport (run, runWithTimeout, writeJson, hashes, splitTab, readInteger)
import System.Directory (createDirectoryIfMissing, doesDirectoryExist, doesFileExist, getCurrentDirectory, listDirectory, removeFile)
import System.Environment (getArgs, lookupEnv)
import System.Exit (die)
import System.FilePath ((</>), takeExtension)

data Family = Bit | IntegerWord | SignedNarrow | Explicit64 deriving (Eq, Show)

data Entry = Entry
  { entryName :: String
  , entryPrimitive :: Maybe String
  , entryWidth :: Int
  , entryArity :: Int
  , entryOperation :: String
  , entryArguments :: [String]
  , entryResult :: String
  , entryUnsigned :: Bool
  , entryIndex :: Int
  }

familyName :: Family -> String
familyName Bit = "bit-primops"
familyName IntegerWord = "integer-primops"
familyName SignedNarrow = "signed-narrow-primops"
familyName Explicit64 = "explicit64-primops"

fixtureModule :: Family -> String
fixtureModule Bit = "BitPrimopsAudit"
fixtureModule IntegerWord = "IntegerPrimopsAudit"
fixtureModule SignedNarrow = "SignedNarrowPrimopsAudit"
fixtureModule Explicit64 = "Explicit64PrimopsAudit"

driverFile :: Family -> String
driverFile Bit = "NativeBitPrimops.hs"
driverFile IntegerWord = "NativeIntegerPrimops.hs"
driverFile SignedNarrow = "NativeSignedNarrowPrimops.hs"
driverFile Explicit64 = "NativeExplicit64.hs"

oracleName :: Family -> String
oracleName Bit = "bit-primops-oracle"
oracleName IntegerWord = "integer-primops-oracle"
oracleName SignedNarrow = "signed-narrow-primops-oracle"
oracleName Explicit64 = "explicit64-oracle"

compositeName :: Family -> Maybe String
compositeName Bit = Just "bitPrimops"
compositeName IntegerWord = Just "composite"
compositeName SignedNarrow = Just "signedNarrowDispatch"
compositeName Explicit64 = Nothing

makeEntry :: Int -> String -> Maybe String -> Int -> Int -> String -> [String] -> String -> Bool -> Entry
makeEntry index name primitive width arity operation arguments result unsigned =
  Entry name primitive width arity operation arguments result unsigned index

entries :: Family -> [Entry]
entries Bit = zipWith makeBit [0 ..] choices
  where
    choices = [(op, width) | (op, widths) <-
      [("popCnt", [8,16,32,64]), ("clz", [8,16,32,64]),
       ("ctz", [8,16,32,64]), ("byteSwap", [16,32,64,0]),
       ("bitReverse", [8,16,32,64,0]), ("narrowWord", [8,16,32])], width <- widths]
    makeBit index (op, width) =
      let suffix = if width == 0 then "Word" else show width
          actualWidth = if width == 0 then 64 else width
          name = if op == "narrowWord" then "narrow" ++ show width ++ "Word" else op ++ suffix
          primitive = if op == "narrowWord" then name ++ "#" else op ++ (if width == 0 then "" else show width) ++ "#"
          argument = if width == 64 then "Word64Rep" else "WordRep"
          result = if width == 64 && op `elem` ["byteSwap", "bitReverse"] then "Word64Rep" else "WordRep"
      in makeEntry index name (Just primitive) actualWidth 1 op [argument] result True
entries IntegerWord = zipWith makeWord [0 ..] choices ++
  zipWith makeDeposit [length choices ..] [(op, width) | op <- ["pdep", "pext"], width <- [8,16,32,64,0]]
  where
    choices = [(op, 64) | op <- ["quot", "rem", "gt", "ge"]] ++
      [(op, width) | op <- ["quot", "rem", "eq", "ne", "gt", "ge", "and", "or", "xor", "not", "uncheckedShiftL", "uncheckedShiftRL"], width <- [8,16,32]]
    makeWord index (op, width) =
      let name = op ++ "Word" ++ (if width == 64 then "" else show width)
          primitive = op ++ "Word" ++ (if width == 64 then "" else show width) ++ "#"
          arity = if op == "not" then 1 else 2
      in makeEntry index name (Just primitive) width arity op (replicate arity "WordRep") "WordRep" True
    makeDeposit index (op, width) =
      let suffix = if width == 0 then "" else show width
          rep = if width == 64 then "Word64Rep" else "WordRep"
      in makeEntry index (op ++ "Word" ++ suffix) (Just (op ++ suffix ++ "#"))
           (if width == 0 then 64 else width) 2 op [rep,rep] rep True
entries SignedNarrow = zipWith makeSigned [0 ..]
  [(op, width) | op <- ["negate", "plus", "sub", "times", "quot", "rem", "eq", "ne", "lt", "le", "gt", "ge"], width <- [8,16,32]] ++
  zipWith makeCast [36 ..] [(direction, width) | width <- [8,16,32], direction <- ["intToWord", "wordToInt"]] ++
  zipWith makeShift [42 ..] [(op, width) | width <- [8,16,32], op <- ["shiftL", "shiftRA"]]
  where
    makeSigned index (op, width) =
      let arity = if op == "negate" then 1 else 2
      in makeEntry index (op ++ "Int" ++ show width) (Just (op ++ "Int" ++ show width ++ "#"))
           width arity op (replicate arity "IntRep") "IntRep" False
    makeCast index (direction, width) =
      let signed = direction == "intToWord"
          name = (if signed then "int" else "word") ++ show width ++
                 "To" ++ (if signed then "Word" else "Int") ++ show width
          argument = (if signed then "Int" else "Word") ++ show width ++ "Rep"
          result = (if signed then "Word" else "Int") ++ show width ++ "Rep"
      in makeEntry index name (Just (name ++ "#")) width 1 direction [argument] result signed
    makeShift index (op, width) =
      let name = "uncheckedShift" ++ (if op == "shiftL" then "L" else "RA") ++ "Int" ++ show width
          rep = "Int" ++ show width ++ "Rep"
      in makeEntry index name (Just (name ++ "#")) width 2 op [rep, "IntRep"] rep False
entries Explicit64 = zipWith make64 [0 ..] definitions
  where
    unary name arg result op unsigned = (name, [arg], result, op, unsigned, True)
    binary name rep result op unsigned = (name, [rep, rep], result, op, unsigned, True)
    definitions =
      [unary "int64ToWord64" "Int64Rep" "Word64Rep" "identity" False,
       unary "word64ToInt64" "Word64Rep" "Int64Rep" "identity" False,
       unary "wordToWord64" "WordRep" "Word64Rep" "identity" False,
       unary "word64ToWord" "Word64Rep" "WordRep" "identity" False] ++
      [binary (op ++ kind ++ "64") (kind ++ "64Rep")
         (if op `elem` comparisons then "IntRep" else kind ++ "64Rep") op (kind == "Word")
       | kind <- ["Int", "Word"], op <- arithmetic] ++
      [unary "negateInt64" "Int64Rep" "Int64Rep" "negate" False] ++
      [(op ++ "64", replicate (if op == "not" then 1 else 2) "Word64Rep", "Word64Rep", op, True, True)
       | op <- ["and", "or", "xor", "not"]] ++
      [(name, [rep, "IntRep"], rep, operation, rep == "Word64Rep", True)
       | (name, rep, operation) <-
         [("uncheckedIShiftL64", "Int64Rep", "shiftL"),
          ("uncheckedIShiftRA64", "Int64Rep", "shiftRA"),
          ("uncheckedIShiftRL64", "Int64Rep", "shiftRL"),
          ("uncheckedShiftL64", "Word64Rep", "shiftL"),
          ("uncheckedShiftRL64", "Word64Rep", "shiftRL")]] ++
      [("word64Literals", ["IntRep"], "Word64Rep", "literals", False, False),
       ("word64Case", ["Word64Rep"], "IntRep", "case", True, False)]
    comparisons = ["eq", "ne", "lt", "le", "gt", "ge"]
    arithmetic = ["plus", "sub", "times", "quot", "rem"] ++ comparisons
    make64 index (name, args, result, op, unsigned, primitive) =
      makeEntry index name (if primitive then Just (name ++ "#") else Nothing)
        64 (length args) op args result unsigned

entryJson :: Family -> Entry -> Value
entryJson Bit e = object
  ["name" .= entryName e, "primitive" .= entryPrimitive e,
   "operation" .= entryOperation e, "width" .= entryWidth e,
   "arity" .= entryArity e,
   "argumentRep" .= case entryArguments e of
     [rep] -> rep
     reps -> error ("Bit fixture must have one argument representation: " ++ show reps),
   "resultRep" .= entryResult e,
   "definedResultBits" .= (if entryOperation e `elem` ["byteSwap", "bitReverse"] then entryWidth e else 64),
   "index" .= entryIndex e]
entryJson IntegerWord e = object
  ["name" .= entryName e, "primitive" .= entryPrimitive e,
   "width" .= entryWidth e, "arity" .= entryArity e, "selector" .= entryIndex e]
entryJson SignedNarrow e = object
  ["name" .= entryName e, "primitive" .= entryPrimitive e,
   "width" .= entryWidth e, "arity" .= entryArity e, "selector" .= entryIndex e]
entryJson Explicit64 e = object
  ["name" .= entryName e, "primitive" .= entryPrimitive e,
   "arguments" .= entryArguments e, "result" .= entryResult e,
   "operation" .= entryOperation e, "unsigned" .= entryUnsigned e,
   "arity" .= entryArity e]

pow2 :: Int -> Integer
pow2 n = 2 ^ n

signed64 :: Integer -> Integer
signed64 n = let residue = n `mod` pow2 64 in
  if residue >= pow2 63 then residue - pow2 64 else residue

samples :: Int -> [Integer]
samples width = Set.toAscList $ Set.fromList $
  [0, 1, 2, 3, mask, mask - 1, mask `div` 2, mask `div` 2 + 1,
   0x5555555555555555 .&. mask, 0xaaaaaaaaaaaaaaaa .&. mask] ++
  [((pow2 bit + delta) .&. mask) | bit <- [0 .. width - 1], delta <- [-1,0,1]]
  where mask = pow2 width - 1

bitOperands :: Int -> [Integer]
bitOperands width = map signed64 $ Set.toAscList $ Set.union (Set.fromList (bitSamples 64)) more
  where
    mask = pow2 width - 1
    bitSamples bits = [0,1,2,3,pow2 bits - 1,pow2 bits - 2,
      0x5555555555555555 .&. (pow2 bits - 1), 0xaaaaaaaaaaaaaaaa .&. (pow2 bits - 1)] ++
      [((pow2 bit + delta) .&. (pow2 bits - 1)) | bit <- [0 .. bits - 1], delta <- [-1,0,1]]
    prefixes = [0,pow2 width,pow2 63,pow2 64 - 1 - mask,
                (pow2 64 - 1 - mask) .&. 0xaaaaaaaaaaaaaaaa]
    low = if width == 8 then [0 .. 255] else bitSamples width
    more = if width == 64 then Set.empty else Set.fromList
      [prefix .|. value | prefix <- prefixes, value <- low]

narrow :: Int -> Integer -> Integer
narrow width value = let half = pow2 (width - 1) in
  (value + half) `mod` (2 * half) - half

signedSamples :: Int -> [Integer]
signedSamples width = Set.toAscList $ Set.fromList $
  [-half, -half + 1, -1, 0, 1, half - 2, half - 1,
   -pow2 63, pow2 63 - 1, -half - 1, half, half + 1,
   -pow2 width - 1, -pow2 width, pow2 width, pow2 width + 1] ++
  [narrow width (sign * pow2 bit + delta) |
    bit <- [0 .. width - 2], sign <- [-1,1], delta <- [-1,0,1]]
  where half = pow2 (width - 1)

operands :: Family -> Entry -> [(Integer,Integer)]
operands Bit e = [(x,0) | x <- bitOperands (entryWidth e)]
operands IntegerWord e
  | entryArity e == 1 = [(signed64 x,0) | x <- if width == 8 then [0 .. 255] else values]
  | entryOperation e `elem` ["pdep", "pext"] =
      [(signed64 x, signed64 y) | x <- depositValues, y <- depositMasks]
  | "unchecked" `isPrefixOf` entryOperation e =
      [(signed64 x, shift) | x <- if width == 8 then [0 .. 255] else values,
       shift <- [0 .. toInteger width - 1]]
  | otherwise = [(signed64 x, signed64 y) | (x,y) <- Set.toAscList pairs,
                 y /= 0 || entryOperation e `notElem` ["quot", "rem"]]
  where
    width = entryWidth e
    depositBits = Set.toAscList $ Set.fromList $ filter (< 64) [0,1,2,width `div` 2,width - 2,width - 1,63]
    depositValues = Set.toAscList $ Set.fromList $
      [0,1,2,3,pow2 width - 1,0x5555555555555555,0xaaaaaaaaaaaaaaaa,pow2 63] ++
      [pow2 bit | bit <- depositBits]
    depositMasks = Set.toAscList $ Set.fromList $
      [0,1,2,3,pow2 width - 1,0x5555555555555555,0xaaaaaaaaaaaaaaaa,pow2 63] ++
      [pow2 bit | bit <- depositBits] ++
      [pow2 bit - 1 | bit <- depositBits, bit > 0]
    values = samples width
    anchors = [0,1,2,3,pow2 (width - 1) - 1,pow2 (width - 1),
               pow2 (width - 1) + 1,pow2 width - 2,pow2 width - 1]
    pairs = Set.fromList ([(x,y) | x <- values, y <- anchors] ++
                          [(x,y) | x <- anchors, y <- values] ++
                          [(x,y) | x <- values, y <- [x-1,x,x+1], y >= 0, y < pow2 width])
operands SignedNarrow e
  | entryArity e == 1 = [(x,0) | x <- Set.toAscList $ Set.union
      (Set.fromList values) (if width == 8 then Set.fromList
        (if entryOperation e == "wordToInt" then [0 .. 255] else [-128 .. 127]) else Set.empty)]
  | entryOperation e `elem` ["shiftL", "shiftRA"] =
      [(x,shift) | x <- if width == 8 then [-128 .. 127] else values,
       shift <- [0 .. toInteger width - 1]]
  | otherwise = filter defined $ Set.toAscList pairs
  where
    width = entryWidth e
    values = signedSamples width
    half = pow2 (width - 1)
    anchors = [-half,-half+1,-3,-1,0,1,3,half-2,half-1]
    pairs = Set.fromList ([(x,y) | x <- values, y <- anchors] ++
                          [(x,y) | x <- anchors, y <- values] ++
                          [(x,y) | x <- values, y <- [x-1,x,x+1],
                           y >= -pow2 63, y < pow2 63])
    defined (x,y) = entryOperation e `notElem` ["quot", "rem"] ||
      (narrow width y /= 0 && (narrow width x,narrow width y) /= (-half,-1))
operands Explicit64 e
  | entryArity e == 1 = [(x,0) | x <- values]
  | entryOperation e `elem` ["shiftL", "shiftRA", "shiftRL"] =
      [(x,n) | x <- values, n <- [0 .. 63]]
  | otherwise = filter defined $ Set.toAscList pairs
  where
    values = explicitSamples
    anchors = [-pow2 63,-pow2 63+1,pow2 63-1,-4097,-1,0,1,3,4097]
    pairs = Set.fromList ([(x,y) | x <- values, y <- anchors] ++
                          [(x,y) | x <- anchors, y <- values] ++
                          [(x,signed64 (x+delta)) | x <- values, delta <- [-1,0,1]])
    defined (x,y) = entryOperation e `notElem` ["quot", "rem"] ||
      (y /= 0 && (entryUnsigned e || (x,y) /= (-pow2 63,-1)))

explicitSamples :: [Integer]
explicitSamples = Set.toAscList $ Set.fromList $
  [-pow2 63,-pow2 63+1,pow2 63-1,pow2 63-2,-1,0,1,2,3,-4097,4097,
   0x5555555555555555,signed64 0xaaaaaaaaaaaaaaaa] ++
  [signed64 (sign * pow2 bit + delta) | bit <- [1,7,8,15,16,31,32,62,63],
    sign <- [-1,1], delta <- [-1,0,1]] ++
  take 24 (map (signed64 . toInteger) (iterate xorshift 641491))
  where
    xorshift :: Integer -> Integer
    xorshift x = let a = x `xor` (x `shiftL` 13)
                     b = a `xor` (a `shiftR` 7)
                 in (b `xor` (b `shiftL` 17)) .&. (pow2 64 - 1)

requestText :: Family -> [(Entry,Integer,Integer)] -> String
requestText family requests = unlines
  [entryName e ++ "\t" ++ show x ++ if family == Bit then "" else "\t" ++ show y |
    (e,x,y) <- requests]

oracleDriver :: Family -> [Entry] -> String
oracleDriver family es = unlines $ header ++ wrappers ++ dispatch ++ ending
  where
    header = ["{-# LANGUAGE MagicHash #-}", "module Main where",
      "import GHC.Exts", "import qualified " ++ fixtureModule family ++ " as P"]
    wrappers = if family == Explicit64 then concatMap hostWrapper es else []
    emitBit = ["emit :: String -> (Int# -> Int#) -> Int -> Int -> IO ()",
      "emit n f (I# mask) x@(I# a) = putStrLn (n ++ \"\\t\" ++ show x ++ \"\\t\" ++ show (I# (andI# (f a) mask)))"]
    emit = ["emit1 :: String -> (Int# -> Int#) -> Int -> IO ()",
      "emit1 n f x@(I# a) = putStrLn (n ++ \"\\t\" ++ show x ++ \"\\t0\\t\" ++ show (I# (f a)))",
      "emit2 :: String -> (Int# -> Int# -> Int#) -> Int -> Int -> IO ()",
      "emit2 n f x@(I# a) y@(I# b) = putStrLn (n ++ \"\\t\" ++ show x ++ \"\\t\" ++ show y ++ \"\\t\" ++ show (I# (f a b)))"]
    dispatch = (if family == Bit then emitBit else emit) ++
      ["dispatch :: [String] -> IO ()",
       "dispatch " ++ (if family == Bit then "[name,x]" else "[name,x,y]") ++ " = case name of"] ++
      map arm es
    arm e = "  " ++ show (entryName e) ++ " -> " ++ case family of
      Bit -> let definedBits = if entryOperation e `elem` ["byteSwap", "bitReverse"] then entryWidth e else 64
             in "emit name P." ++ entryName e ++ " (" ++ show (signed64 (pow2 definedBits - 1)) ++ ") (read x)"
      _ -> let function = if family == Explicit64 then "host_" ++ entryName e else "P." ++ entryName e
           in "emit" ++ show (entryArity e) ++ " name " ++ function ++ " (read x)" ++
              (if entryArity e == 2 then " (read y)" else "")
    ending = ["  _ -> error \"unknown primitive\"", "dispatch _ = error \"invalid input\"",
      "main :: IO ()", "main = getContents >>= mapM_ (dispatch . words) . lines"]

hostWrapper :: Entry -> [String]
hostWrapper e =
  ["host_" ++ entryName e ++ " :: " ++ concat (replicate (entryArity e) "Int# -> ") ++ "Int#",
   "host_" ++ entryName e ++ " " ++ unwords names ++ " = " ++ output (entryResult e)
      ("P." ++ entryName e ++ " " ++ unwords (zipWith input (entryArguments e) names))]
  where
    names = take (entryArity e) ["x","y"]
    input "IntRep" x = x
    input "WordRep" x = "(int2Word# " ++ x ++ ")"
    input "Int64Rep" x = "(intToInt64# " ++ x ++ ")"
    input "Word64Rep" x = "(wordToWord64# (int2Word# " ++ x ++ "))"
    input rep _ = error ("Unsupported input rep: " ++ rep)
    output "IntRep" x = x
    output "WordRep" x = "word2Int# (" ++ x ++ ")"
    output "Int64Rep" x = "int64ToInt# (" ++ x ++ ")"
    output "Word64Rep" x = "word2Int# (word64ToWord# (" ++ x ++ "))"
    output rep _ = error ("Unsupported output rep: " ++ rep)

relativeCore :: Family -> String -> [FilePath]
relativeCore family stage =
  let dir = "build" </> familyName family </> stage
  in if family == Explicit64 then [dir </> fixtureModule family ++ ".json"] else
       [dir </> fixtureModule family ++ ".json", dir </> "THC.InterfaceClosure.json"]

inputPaths :: FilePath -> Family -> IO [FilePath]
inputPaths root family = do
  plugin <- listDirectory (root </> "compiler/THC")
  let source = "compiler/test-fixtures" </> fixtureModule family ++ ".hs"
  pure $ sort $ [source, "thc.cabal", "test/haskell-fixtures/Main.hs", "test/haskell-fixtures/FixtureSupport.hs",
    "compiler/build.sh", "compiler/export.sh", "compiler/toolchain.sh", "compiler/plugin.py"] ++
    ["compiler/THC" </> file | file <- plugin, takeExtension file == ".hs"]

prepare :: FilePath -> Family -> IO ()
prepare root family = do
  let directory = "build" </> familyName family
      output = root </> directory
      manifest = output </> "manifest.json"
      es = entries family
      source = "compiler/test-fixtures" </> fixtureModule family ++ ".hs"
      stages = if family == Bit then ["pre-core", "post-core"] else ["core"]
  createDirectoryIfMissing True output
  present <- doesFileExist manifest
  when present (removeFile manifest)
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- run root [] ghc ["--numeric-version"] ""
  unless (takeWhile (/= '\n') version == "9.14.1") (die "thc-fixtures requires GHC 9.14.1")
  let roots = maybe [] (\name -> ["-fplugin-opt=THC.Plugin:closure=" ++ name]) (compositeName family) ++
        ["-fplugin-opt=THC.Plugin:closure=" ++ entryName e | e <- es]
  stageArtifacts <- forM stages $ \stage -> do
    let core = directory </> stage
        ghcOut = directory </> (stage ++ "-ghc")
        options = if stage == "post-core" then ["-fplugin-opt=THC.Plugin:post-tidy"] else []
        exportArgs = options ++ roots ++ [source]
    _ <- run root [("THC_CORE_OUT",root </> core),("THC_GHC_OUT",root </> ghcOut)]
      "compiler/export.sh" exportArgs ""
    let modules = relativeCore family stage
    forM_ modules $ \path -> do
      exists <- doesFileExist (root </> path)
      unless exists (die ("Missing GHC Core export: " ++ path))
    pure (stage,modules)
  let driver = directory </> driverFile family
      requests = [(e,x,y) | e <- es, (x,y) <- operands family e]
      stdinText = requestText family requests
      nativeDir = directory </> "native"
      executable = nativeDir </> oracleName family
      oracle = directory </> "oracle.tsv"
  writeFile (root </> driver) (oracleDriver family es)
  createDirectoryIfMissing True (root </> nativeDir)
  _ <- run root [] ghc ["--make", "-O2", "-fforce-recomp", "-dcore-lint", "-dstg-lint",
     "-i" ++ (root </> "compiler/test-fixtures"), "-odir", root </> nativeDir,
     "-hidir", root </> nativeDir, root </> driver, "-o", root </> executable] ""
  actual <- runWithTimeout (Just (60 * 1000000)) root [] (root </> executable) [] stdinText
  let expectedKeys = Set.fromList [(entryName e,x,y) | (e,x,y) <- requests]
      rows = map (splitTab . takeWhile (/= '\r')) (lines actual)
      parseRow fields = case fields of
        [name,x,result] | family == Bit -> do
          left <- readInteger x
          _ <- readInteger result
          pure (name,left,0)
        [name,x,y,result] -> do
          left <- readInteger x
          right <- readInteger y
          _ <- readInteger result
          pure (name,left,right)
        _ -> Nothing
  parsed <- maybe (die "Malformed native oracle TSV") pure (traverse parseRow rows)
  unless (length parsed == Set.size expectedKeys && Set.fromList parsed == expectedKeys)
    (die "Native oracle returned missing, duplicate, or unexpected inputs")
  writeFile (root </> oracle) actual
  sources <- inputPaths root family
  let modules = concatMap snd stageArtifacts
      artifacts = modules ++ [driver,oracle]
  sourceHashes <- hashes root sources
  artifactHashes <- hashes root artifacts
  ghcInfo <- if family == Explicit64 then run root [] ghc ["--info"] "" else pure ""
  let common = ["schema" .= (1 :: Int), "entries" .= map (entryJson family) es,
                "inputHashes" .= sourceHashes, "artifactHashes" .= artifactHashes]
      details = case family of
        Bit -> ["ghc" .= ("9.14.1" :: String),
          "stages" .= case stageArtifacts of
            [(_,pre),(_,post)] -> object ["pre" .= pre, "post" .= post]
            _ -> error "Bit fixture requires pre/post GHC Core exports",
          "compositeEntry" .= ("bitPrimops" :: String), "nativeRows" .= length parsed,
          "nativeResultPolicy" .= ("Mask only GHC-defined bits; THC checks canonical zero upper bits directly" :: String)]
        IntegerWord -> ["composite" .= object
          ["name" .= ("composite" :: String), "arity" .= (3 :: Int),
           "selectorArgument" .= (0 :: Int), "selectorOrder" .= map entryName es],
          "modules" .= modules]
        SignedNarrow -> ["modules" .= modules, "compositeEntry" .= ("signedNarrowDispatch" :: String),
          "nativeRows" .= length parsed,
          "excludedDivisionInputs" .= (["zero narrowed divisor", "narrow minBound / -1"] :: [String])]
        Explicit64 -> ["ghc" .= ("9.14.1" :: String), "ghcInfo" .= ghcInfo,
          "commands" .= ([] :: [[String]]),
          "excludedInputs" .= (["zero divisors", "signed minBound / -1 (quotient and remainder)",
                                 "shift counts outside [0,64)"] :: [String])]
  writeJson manifest (object (common ++ details))
  putStrLn (familyName family ++ ": " ++ show (length es) ++ " entries, " ++
            show (length parsed) ++ " native rows, " ++ show (length stageArtifacts) ++
            " GHC Core export stage" ++ (if length stageArtifacts == 1 then "" else "s"))

data ArrayGroup = ArrayGroup
  { arraySource :: FilePath
  , arrayModule :: String
  , arrayPrefix :: String
  , arrayEntries :: [String]
  }

data ArrayInputDomain = SharedInputs [Integer] | PerEntryInputs (Map.Map String [Integer])

data ArraySpec = ArraySpec
  { arrayName :: String
  , arrayGroups :: [ArrayGroup]
  , arrayDriver :: FilePath
  , arrayOracle :: FilePath
  , arrayInputDomain :: ArrayInputDomain
  , arrayElementBits :: Maybe Int
  , arrayLiterals :: [String]
  , arrayLiteralInputs :: [Integer]
  , arrayNaNHelpers :: [String]
  }

basicArray :: String -> [ArrayGroup] -> FilePath -> FilePath -> ArrayInputDomain -> ArraySpec
basicArray name groups driver oracle inputs = ArraySpec name groups driver oracle inputs Nothing [] [] []

arraySpec :: String -> Maybe ArraySpec
arraySpec "int-arrays" = Just $ basicArray "int-arrays"
  [ArrayGroup "examples/THC/UnboxedArrays.hs" "THC.UnboxedArrays" "U"
    ["unboxedAccum", "unboxedST", "unboxedEmpty"],
   ArrayGroup "compiler/test-fixtures/IntArrayAudit.hs" "IntArrayAudit" "P"
    ["orderedInts", "aliasIntBytes"]]
  "NativeIntArray.hs" "int-array-oracle" (SharedInputs $ arrayBoundaryInputs [-16 .. 16]
    [0x5555555555555555, 0xaaaaaaaaaaaaaaaa, 0x55aa55aa55aa55aa,
     0xaa55aa55aa55aa55, 0x0123456789abcdef, 0xfedcba9876543210])
arraySpec "int8-arrays" = Just $ (basicArray "int8-arrays"
  [ArrayGroup "examples/THC/Unboxed8Arrays.hs" "THC.Unboxed8Arrays" "U"
    ["unboxedInt8Accum", "unboxedInt8ST", "unboxedWord8Accum", "unboxedWord8ST"],
   ArrayGroup "compiler/test-fixtures/Int8ArrayAudit.hs" "Int8ArrayAudit" "P"
    ["aliasBytes", "emptyBytes", "rawSignedRead", "rawUnsignedRead", "rawSignedIndex"]]
  "NativeInt8Array.hs" "int8-array-oracle" (SharedInputs $ arrayBitInputs [-256 .. 255]
    [0x5555555555555555, 0xaaaaaaaaaaaaaaaa, 0x0123456789abcdef, 0xfedcba9876543210]))
  {arrayElementBits = Just 8}
arraySpec "int16-arrays" = Just $ (basicArray "int16-arrays"
  [ArrayGroup "examples/THC/Unboxed16Arrays.hs" "THC.Unboxed16Arrays" "U"
    ["unboxedInt16Accum", "unboxedInt16ST", "unboxedWord16Accum", "unboxedWord16ST"],
   ArrayGroup "compiler/test-fixtures/Int16ArrayAudit.hs" "Int16ArrayAudit" "P"
    ["aliasInt16Bytes", "aliasWord16Bytes"]]
  "NativeInt16Array.hs" "int16-array-oracle" (SharedInputs $ arrayBoundaryInputs [-16 .. 16]
    [0x5555555555555555, 0xaaaaaaaaaaaaaaaa, 0x55aa55aa55aa55aa,
     0xaa55aa55aa55aa55, 0x0123456789abcdef, 0xfedcba9876543210,
     0x8000000080000000, 0xffffffff00000000, 0x800000007fffffff,
     0x7fffffff80000000, 0xffffffff7fffffff, 0x0000000100000001,
     0x12345678abcdef01, 0x80008000, 0xffff0000, 0x80007fff,
     0x7fff8000, 0xffff7fff, 0x00010001, 0x12345678abcd8000]))
  {arrayElementBits = Just 16,
   arrayLiterals = ["noinlineInt16Literal", "noinlineWord16Literal"],
   arrayLiteralInputs = [-pow2 63, -32768, -1, 0, 1, 32767, pow2 63 - 1]}
arraySpec "int32-arrays" = Just $ (basicArray "int32-arrays"
  [ArrayGroup "examples/THC/Unboxed32Arrays.hs" "THC.Unboxed32Arrays" "U"
    ["unboxedInt32Accum", "unboxedInt32ST", "unboxedWord32Accum", "unboxedWord32ST"],
   ArrayGroup "compiler/test-fixtures/Int32ArrayAudit.hs" "Int32ArrayAudit" "P"
    ["aliasInt32Bytes", "aliasWord32Bytes"]]
  "NativeInt32Array.hs" "int32-array-oracle" (SharedInputs $ arrayBoundaryInputs [-16 .. 16]
    [0x5555555555555555, 0xaaaaaaaaaaaaaaaa, 0x55aa55aa55aa55aa,
     0xaa55aa55aa55aa55, 0x0123456789abcdef, 0xfedcba9876543210,
     0x8000000080000000, 0xffffffff00000000, 0x800000007fffffff,
     0x7fffffff80000000, 0xffffffff7fffffff, 0x0000000100000001,
     0x12345678abcdef01]))
  {arrayElementBits = Just 32,
   arrayLiterals = ["noinlineInt32Literal", "noinlineWord32Literal"],
   arrayLiteralInputs = [-pow2 63, -2147483648, -1, 0, 1, 2147483647, pow2 63 - 1]}
arraySpec "double-arrays" = Just $ (basicArray "double-arrays"
  [ArrayGroup "examples/THC/UnboxedDoubleArrays.hs" "THC.UnboxedDoubleArrays" "U"
    ["unboxedDoubleAccum", "unboxedDoubleST"],
   ArrayGroup "compiler/test-fixtures/DoubleArrayAudit.hs" "DoubleArrayAudit" "P"
    ["moveDoubleBits", "indexDoubleBits"]]
  "NativeDoubleArray.hs" "double-array-oracle" (SharedInputs doubleArrayInputs))
  {arrayNaNHelpers = ["moveDoubleBits", "indexDoubleBits"]}
arraySpec "float-word-arrays" = Just $ (basicArray "float-word-arrays"
  [ArrayGroup "examples/THC/UnboxedFloatArrays.hs" "THC.UnboxedFloatArrays" "F"
    ["unboxedFloatAccum", "unboxedFloatST"],
   ArrayGroup "examples/THC/UnboxedWordArrays.hs" "THC.UnboxedWordArrays" "W"
    ["unboxedWordAccum", "unboxedWordST"],
   ArrayGroup "compiler/test-fixtures/FloatArrayAudit.hs" "FloatArrayAudit" "P"
    ["moveFloatBits", "indexFloatBits"],
   ArrayGroup "compiler/test-fixtures/WordArrayAudit.hs" "WordArrayAudit" "Q"
    ["aliasWordBytes"]]
  "NativeFloatWordArray.hs" "float-word-array-oracle" (PerEntryInputs floatWordInputs))
  {arrayNaNHelpers = ["moveFloatBits", "indexFloatBits"]}
arraySpec _ = Nothing

arrayBoundaryInputs :: [Integer] -> [Integer] -> [Integer]
arrayBoundaryInputs initial patterns = Set.toAscList $ Set.fromList $
  arrayBitInputs initial patterns ++ [-pow2 63, -pow2 63 + 1, pow2 63 - 2, pow2 63 - 1]

arrayBitInputs :: [Integer] -> [Integer] -> [Integer]
arrayBitInputs initial patterns = Set.toAscList $ Set.fromList $
  initial ++
  [signed64 (sign * (pow2 bit + delta)) |
    bit <- [0 .. 63], delta <- [-1,0,1], sign <- [-1,1]] ++ map signed64 patterns

doubleArrayInputs :: [Integer]
doubleArrayInputs = filter (not . signalingDouble) $ Set.toAscList $ Set.fromList $
  arrayBoundaryInputs [-16 .. 16] [] ++
  [signed64 (bits .|. sign) | bits <- magnitudes, sign <- [0,pow2 63]] ++
  [signed64 (0x7ff8000000000000 .|. pow2 bit .|. sign) |
    bit <- [0 .. 50], sign <- [0,pow2 63]]
  where
    magnitudes = [0,1,2,3,0x000fffffffffffff,0x0010000000000000,
      0x3fefffffffffffff,0x3ff0000000000000,0x3ff0000000000001,
      0x7fefffffffffffff,0x7ff0000000000000,0x7ff8000000000000,
      0x7ff8000000001234,0x7fffffffffffffff,0x5555555555555555,
      0x55aa55aa55aa55aa,0x0123456789abcdef]

signalingDouble :: Integer -> Bool
signalingDouble value = let bits = value .&. (pow2 64 - 1)
                            fraction = bits .&. (pow2 52 - 1)
                        in bits .&. 0x7ff0000000000000 == 0x7ff0000000000000 &&
                           fraction /= 0 && bits .&. pow2 51 == 0

floatWordInputs :: Map.Map String [Integer]
floatWordInputs = Map.fromList
  [(name, if name `elem` helpers then movement else regular) | name <- names]
  where
    helpers = ["moveFloatBits", "indexFloatBits"]
    names = ["unboxedFloatAccum", "unboxedFloatST", "unboxedWordAccum", "unboxedWordST",
             "moveFloatBits", "indexFloatBits", "aliasWordBytes"]
    regular = arrayBoundaryInputs [-16 .. 16]
      [0x5555555555555555,0xaaaaaaaaaaaaaaaa,0x55aa55aa55aa55aa,
       0xaa55aa55aa55aa55,0x0123456789abcdef,0xfedcba9876543210,
       0x8000000080000000,0xffffffff00000000,0x800000007fffffff,
       0x7fffffff80000000,0xffffffff7fffffff,0x0000000100000001,
       0x12345678abcdef01]
    magnitudes = [0,1,2,3,0x007fffff,0x00800000,0x3f7fffff,0x3f800000,
      0x3f800001,0x7f7fffff,0x7f800000,0x7fc00000,0x7fc01234,0x7fffffff]
    bits = Set.toAscList $ Set.fromList $
      [magnitude .|. sign | magnitude <- magnitudes, sign <- [0,pow2 31]] ++
      [0x7fc00000 .|. pow2 bit .|. sign | bit <- [0 .. 21], sign <- [0,pow2 31]]
    movement = filter (not . signalingFloat) $ Set.toAscList $ Set.fromList $
      regular ++ [signed64 (value .|. upper) | value <- bits,
                  upper <- [0,0x1234567800000000,0xffffffff00000000]]

signalingFloat :: Integer -> Bool
signalingFloat value = let bits = value .&. (pow2 32 - 1)
                           fraction = bits .&. (pow2 23 - 1)
                       in bits .&. 0x7f800000 == 0x7f800000 &&
                          fraction /= 0 && bits .&. pow2 22 == 0

arrayDriverSource :: ArraySpec -> String
arrayDriverSource spec = unlines $ header ++ map arm (allEntries ++ literalEntries) ++ ending
  where
    groups = arrayGroups spec
    allEntries = [(name, arrayPrefix group) | group <- groups, name <- arrayEntries group]
    literalEntries = [(name,"P") | name <- arrayLiterals spec]
    header = ["{-# LANGUAGE MagicHash #-}", "module Main where",
      "import GHC.Exts", "import Data.Bits (finiteBitSize)"] ++
      ["import qualified " ++ arrayModule group ++ " as " ++ arrayPrefix group | group <- groups] ++
      ["emit :: String -> (Int# -> Int#) -> Int -> IO ()",
       "emit name f x@(I# a) = putStrLn (name ++ \"\\t\" ++ show x ++ \"\\t\" ++ show (I# (f a)))",
       "dispatch :: [String] -> IO ()", "dispatch [name,x] = case name of"]
    arm (name,prefix) = "  " ++ show name ++ " -> emit name " ++ function name prefix ++ " (read x)"
    function "rawUnsignedRead" prefix =
      "(\\a -> word2Int# (word8ToWord# (" ++ prefix ++ ".rawUnsignedRead a)))"
    function name prefix
      | name `elem` ["rawSignedRead", "rawSignedIndex"] =
          "(\\a -> int8ToInt# (" ++ prefix ++ "." ++ name ++ " a))"
      | otherwise = prefix ++ "." ++ name
    ending = ["  _ -> error \"unknown entry\"", "dispatch _ = error \"invalid input\"",
      "main :: IO ()", "main = if finiteBitSize (0 :: Int) /= 64 then error \"Requires 64-bit Int\"",
      "       else getContents >>= mapM_ (dispatch . words) . lines"]

nativeByteOrder :: IO String
nativeByteOrder = alloca $ \ptr -> do
  poke ptr (1 :: Word16)
  byte <- peek (castPtr ptr :: Ptr Word8)
  pure (if byte == 1 then "little" else "big")

prepareArray :: FilePath -> ArraySpec -> IO ()
prepareArray root spec = do
  let directory = "build" </> arrayName spec
      output = root </> directory
      manifest = output </> "manifest.json"
      groups = arrayGroups spec
      names = concatMap arrayEntries groups
      inputFor name = case arrayInputDomain spec of
        SharedInputs values -> values
        PerEntryInputs values -> Map.findWithDefault [] name values
      literals = arrayLiterals spec
      literalRequests = [(name,value) | name <- literals, value <- arrayLiteralInputs spec]
  createDirectoryIfMissing True output
  present <- doesFileExist manifest
  when present (removeFile manifest)
  staleExpected <- doesFileExist (output </> "expected.tsv")
  when staleExpected (removeFile (output </> "expected.tsv"))
  forM_ ["pre", "post"] $ \stage -> do
    let stageDir = output </> stage
    stagePresent <- doesDirectoryExist stageDir
    when stagePresent $ do
      oldReports <- listDirectory stageDir
      forM_ (filter (isSuffixOf ".audit.json") oldReports) $ \file ->
        removeFile (stageDir </> file)
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  ghcPkg <- maybe "ghc-pkg" id <$> lookupEnv "GHC_PKG"
  version <- run root [] ghc ["--numeric-version"] ""
  unless (takeWhile (/= '\n') version == "9.14.1") (die "thc-fixtures requires GHC 9.14.1")
  arrayVersion <- run root [] ghcPkg ["field", "array", "version", "--simple-output"] ""
  unless (takeWhile (/= '\n') arrayVersion == "0.5.8.0") (die "thc-fixtures requires array 0.5.8.0")
  stages <- forM [("pre", "optimized-Core-before-Tidy"),
                   ("post", "optimized-Core-after-Tidy-before-CorePrep")] $ \(stage,boundary) -> do
    paths <- fmap concat $ forM (zip [0 :: Int ..] groups) $ \(index,group) -> do
      let folder = directory </> stage </> show index
          core = folder </> "core"
          modulePath = core </> arrayModule group ++ ".json"
          closurePath = core </> "THC.InterfaceClosure.json"
          options = if stage == "post" then ["-fplugin-opt=THC.Plugin:post-tidy"] else []
          roots = ["-fplugin-opt=THC.Plugin:closure=" ++ name | name <- arrayEntries group]
      _ <- run root [("THC_CORE_OUT",root </> core), ("THC_GHC_OUT",root </> folder </> "ghc"),
                     ("THC_SOURCE_NOTES","true")]
        "compiler/export.sh" (options ++ roots ++ [arraySource group]) ""
      content <- BS.readFile (root </> modulePath)
      let exportedBoundary = case decodeStrict' content of
            Just (Object value) -> KeyMap.lookup "boundary" value
            _ -> Nothing
      unless (exportedBoundary == Just (String (fromString boundary)))
        (die ("Wrong GHC Core boundary: " ++ modulePath))
      closurePresent <- doesFileExist (root </> closurePath)
      unless closurePresent (die ("Missing GHC interface closure: " ++ closurePath))
      pure [modulePath, closurePath]
    pure (stage,paths)
  let driver = directory </> arrayDriver spec
      binary = directory </> "native" </> arrayOracle spec
      oracle = directory </> "oracle.tsv"
      requests = [(name,value) | name <- names, value <- inputFor name]
      arrayRequestText = unlines [name ++ "\t" ++ show value | (name,value) <- requests]
  unless (Set.size (Set.fromList names) == length names &&
          all (not . null . inputFor) names &&
          (case arrayInputDomain spec of
             SharedInputs _ -> True
             PerEntryInputs values -> Map.keysSet values == Set.fromList names))
    (die "Invalid array entry or input declaration")
  writeFile (root </> driver) (arrayDriverSource spec)
  createDirectoryIfMissing True (root </> directory </> "native")
  _ <- run root [] ghc ["--make", "-O2", "-fforce-recomp", "-dcore-lint", "-dstg-lint",
    "-i" ++ root </> "examples", "-i" ++ root </> "compiler/test-fixtures",
    "-odir", root </> directory </> "native", "-hidir", root </> directory </> "native",
    root </> driver, "-o", root </> binary] ""
  actual <- run root [] (root </> binary) [] arrayRequestText
  let parsed = traverse parseArrayRow (lines actual)
      expectedKeys = Set.fromList requests
  rows <- maybe (die "Malformed native array oracle TSV") pure parsed
  unless (length rows == Set.size expectedKeys && Set.fromList rows == expectedKeys)
    (die "Native array oracle returned missing, duplicate, or unexpected inputs")
  writeFile (root </> oracle) actual
  literalArtifacts <- if null literals then pure [] else do
    let literalOracle = directory </> "literal-oracle.tsv"
        literalRequestText = unlines [name ++ "\t" ++ show value | (name,value) <- literalRequests]
    literalActual <- run root [] (root </> binary) [] literalRequestText
    literalRows <- maybe (die "Malformed native literal oracle TSV") pure $
      traverse parseArrayRow (lines literalActual)
    let literalKeys = Set.fromList literalRequests
    unless (length literalRows == Set.size literalKeys && Set.fromList literalRows == literalKeys)
      (die "Native literal oracle returned missing, duplicate, or unexpected inputs")
    writeFile (root </> literalOracle) literalActual
    pure [literalOracle]
  plugin <- listDirectory (root </> "compiler/THC")
  let sources = sort $ ["thc.cabal", "test/haskell-fixtures/Main.hs", "test/haskell-fixtures/FixtureSupport.hs", "compiler/build.sh",
        "compiler/export.sh", "compiler/toolchain.sh", "compiler/plugin.py"] ++
        map arraySource groups ++ ["compiler/THC" </> file | file <- plugin, takeExtension file == ".hs"]
      artifacts = concatMap snd stages ++ [driver,binary,oracle] ++ literalArtifacts
  sourceHashes <- hashes root sources
  artifactHashes <- hashes root artifacts
  byteOrder <- nativeByteOrder
  installedArray <- run root [] ghcPkg ["describe", "array"] ""
  ghcInfo <- run root [] ghc ["--info"] ""
  let inputFields = case arrayInputDomain spec of
        SharedInputs values -> ["inputs" .= values]
        PerEntryInputs values -> ["inputsByEntry" .= values]
      widthFields = maybe [] (\bits -> ["elementBits" .= bits]) (arrayElementBits spec)
      literalFields = if null literals then [] else
        ["literalEntries" .= literals, "literalInputs" .= arrayLiteralInputs spec,
         "literalNativeRows" .= length literalRequests]
      nanFields = if null (arrayNaNHelpers spec) then [] else
        ["signalingNaNsExcluded" .= True,
         "signalingNaNExclusionScope" .= arrayNaNHelpers spec]
      expectedCalls = Map.fromList
        [(name, if name `elem` arrayNaNHelpers spec then (3 :: Int) else 2) | name <- names]
  writeJson manifest $ object $
    ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String), "array" .= ("0.5.8.0" :: String),
     "wordBits" .= (64 :: Int), "byteOrder" .= byteOrder,
     "entries" .= names, "nativeRows" .= length rows,
     "expectedGuestCallsByEntry" .= expectedCalls,
     "stages" .= Map.fromList stages, "installedArray" .= installedArray, "ghcInfo" .= ghcInfo,
     "inputHashes" .= sourceHashes, "artifactHashes" .= artifactHashes,
     "claim" .= ("Native GHC oracle and pre/post Core exports; JVM tests own independent semantic and structural validation" :: String)] ++
     inputFields ++ widthFields ++ literalFields ++ nanFields
  putStrLn (arrayName spec ++ ": " ++ show (length names) ++ " entries, " ++
            show (length rows) ++ " native rows, pre/post GHC Core")

parseArrayRow :: String -> Maybe (String,Integer)
parseArrayRow line = case splitTab (takeWhile (/= '\r') line) of
  [name,input,result] -> do
    value <- readInteger input
    _ <- readInteger result
    pure (name,value)
  _ -> Nothing

preparePinnedPointers :: FilePath -> IO ()
preparePinnedPointers root = do
  let directory = "build/pinned-pointer-cells"
      manifest = root </> directory </> "manifest.json"
      native = directory </> "native"
      binary = native </> "oracle"
      source = "compiler/test-fixtures/PinnedPointerCellsAudit.hs"
      driver = "compiler/test-fixtures/PinnedPointerCellsNative.hs"
  createDirectoryIfMissing True (root </> native)
  present <- doesFileExist manifest
  when present (removeFile manifest)
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- takeWhile (/= '\n') <$> run root [] ghc ["--numeric-version"] ""
  unless (version == "9.14.1") (die "Pinned pointer fixture requires GHC 9.14.1")
  _ <- run root [] ghc ["--make", "-O2", "-dynamic", "-fforce-recomp", "-dcore-lint",
    "-i./compiler/test-fixtures", "-odir", native, "-hidir", native, driver, "-o", binary] ""
  oracle <- run root [] (root </> binary) [] (unlines (map show ([0,1,17,127,255,256,-1] :: [Int])))
  writeFile (root </> directory </> "oracle.tsv") oracle
  forM_ ["pre", "post"] $ \stage -> do
    let core = directory </> stage </> "core"
        ghcOut = directory </> stage </> "ghc"
        options = if stage == "post" then ["-fplugin-opt=THC.Plugin:post-tidy"] else []
    forM_ [core, ghcOut] (createDirectoryIfMissing True . (root </>))
    _ <- run root [("THC_CORE_OUT", root </> core), ("THC_GHC_OUT", root </> ghcOut)]
      "compiler/export.sh" (options ++ ["-fplugin-opt=THC.Plugin:closure=pointerRoundtrip",
        "-fplugin-opt=THC.Plugin:closure=pointerArrayRoundtrip",
        "-fplugin-opt=THC.Plugin:closure=pointerOrder",
        "-fplugin-opt=THC.Plugin:closure=char8Roundtrip",
        "-fplugin-opt=THC.Plugin:closure=byte8Roundtrip", source]) ""
    _ <- run root [] "python3" ["scripts/audit-core.py", "--entry", "pointerRoundtrip",
      "--entry", "pointerArrayRoundtrip", "--entry", "pointerOrder", "--entry", "char8Roundtrip",
      "--entry", "byte8Roundtrip",
      "--output", directory </> stage </> "audit.json",
      core </> "PinnedPointerCellsAudit.json", core </> "THC.InterfaceClosure.json"] ""
    pure ()
  plugin <- listDirectory (root </> "compiler/THC")
  scripts <- listDirectory (root </> "scripts")
  let inputs = sort $ [source, driver, "test/haskell-fixtures/Main.hs", "test/haskell-fixtures/FixtureSupport.hs", "scripts/audit-core.py",
        "scripts/core-capabilities.json", "compiler/export.sh", "compiler/build.sh",
        "compiler/toolchain.sh", "compiler/plugin.py", "thc.cabal", "cabal.project"] ++
        ["compiler/THC" </> file | file <- plugin, takeExtension file == ".hs"] ++
        ["scripts" </> file | file <- scripts, "core_" `isPrefixOf` file, takeExtension file == ".py"]
      artifacts = (directory </> "oracle.tsv") :
        [directory </> stage </> file | stage <- ["pre", "post"],
          file <- ["audit.json", "core/PinnedPointerCellsAudit.json", "core/THC.InterfaceClosure.json"]]
  inputHashes <- hashes root inputs
  artifactHashes <- hashes root artifacts
  writeJson manifest $ object ["schema" .= (1 :: Int), "ghc" .= version,
    "inputHashes" .= inputHashes, "artifactHashes" .= artifactHashes]
  putStrLn "pinned-pointer-cells: 7 native rows, five strict pre/post Core roots"

main :: IO ()
main = do
  args <- getArgs
  root <- getCurrentDirectory
  exists <- doesFileExist (root </> "thc.cabal")
  unless exists (die "Run thc-fixtures from the THC repository root")
  handled <- case args of
    [name] -> prepareAggregate root name
    _ -> pure False
  unless handled $ case args of
    ["word-floating"] -> prepareWordFloating root
    "original-stdio":options -> prepareOriginalStdio root options
    ["original-stack"] -> prepareOriginalStack root
    ["boxed-array-extensions"] -> prepareBoxedArrayExtensions root
    ["bit"] -> prepare root Bit
    ["integer"] -> prepare root IntegerWord
    ["signed-narrow"] -> prepare root SignedNarrow
    ["explicit64"] -> prepare root Explicit64
    ["pinned-pointer-cells"] -> preparePinnedPointers root
    ["core-continuation"] -> prepareCoreContinuation root
    ["small-arrays"] -> prepareSmallArrays root
    _ | not (null args), Just specs <- traverse arraySpec args -> mapM_ (prepareArray root) specs
    _ -> die "Usage: thc-fixtures (core-continuation|original-stack|boxed-array-extensions|original-stdio [OPTIONS]|bit|integer|signed-narrow|explicit64|word-floating|tuple-arithmetic|pinned-pointer-cells|small-arrays|int-arrays|int8-arrays|int16-arrays|int32-arrays|double-arrays|float-word-arrays ...)"
