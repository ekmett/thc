-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE OverloadedStrings #-}

-- Native fixture production belongs to Haskell. The JVM tests own the
-- independent arithmetic models and compiled guest comparisons.
module Main (main) where

import Control.Monad (forM, forM_, unless, when)
import qualified Crypto.Hash.SHA256 as SHA256
import Data.Aeson (Value, object, (.=), encode)
import Data.Bits ((.&.), (.|.), xor, shiftL, shiftR)
import qualified Data.ByteString as BS
import qualified Data.ByteString.Lazy as BL
import Data.List (isPrefixOf, sort)
import qualified Data.Map.Strict as Map
import qualified Data.Set as Set
import Numeric (showHex)
import System.Directory (createDirectoryIfMissing, doesFileExist, getCurrentDirectory, listDirectory, removeFile)
import System.Environment (getArgs, getEnvironment, lookupEnv)
import System.Exit (ExitCode (..), die)
import System.FilePath ((</>), takeExtension)
import System.Process (CreateProcess (..), proc, readCreateProcessWithExitCode)

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
       ("bitReverse", [8,16,32,64,0])], width <- widths]
    makeBit index (op, width) =
      let suffix = if width == 0 then "Word" else show width
          actualWidth = if width == 0 then 64 else width
          primitive = op ++ (if width == 0 then "" else show width) ++ "#"
          argument = if width == 64 then "Word64Rep" else "WordRep"
          result = if width == 64 && op `elem` ["byteSwap", "bitReverse"] then "Word64Rep" else "WordRep"
      in makeEntry index (op ++ suffix) (Just primitive) actualWidth 1 op [argument] result True
entries IntegerWord = zipWith makeWord [0 ..] choices
  where
    choices = [(op, 64) | op <- ["quot", "rem", "gt", "ge"]] ++
      [(op, width) | op <- ["quot", "rem", "eq", "ne", "gt", "ge", "and", "or", "xor", "not", "uncheckedShiftL", "uncheckedShiftRL"], width <- [8,16,32]]
    makeWord index (op, width) =
      let name = op ++ "Word" ++ (if width == 64 then "" else show width)
          primitive = op ++ "Word" ++ (if width == 64 then "" else show width) ++ "#"
          arity = if op == "not" then 1 else 2
      in makeEntry index name (Just primitive) width arity op (replicate arity "WordRep") "WordRep" True
entries SignedNarrow = zipWith makeSigned [0 ..]
  [(op, width) | op <- ["negate", "plus", "sub", "times", "quot", "rem", "eq", "ne", "lt", "le", "gt", "ge"], width <- [8,16,32]]
  where
    makeSigned index (op, width) =
      let arity = if op == "negate" then 1 else 2
      in makeEntry index (op ++ "Int" ++ show width) (Just (op ++ "Int" ++ show width ++ "#"))
           width arity op (replicate arity "IntRep") "IntRep" False
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
   "argumentRep" .= (if entryWidth e == 64 then "Word64Rep" else "WordRep" :: String),
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
  | "unchecked" `isPrefixOf` entryOperation e =
      [(signed64 x, shift) | x <- if width == 8 then [0 .. 255] else values,
       shift <- [0 .. toInteger width - 1]]
  | otherwise = [(signed64 x, signed64 y) | (x,y) <- Set.toAscList pairs,
                 y /= 0 || entryOperation e `notElem` ["quot", "rem"]]
  where
    width = entryWidth e
    values = samples width
    anchors = [0,1,2,3,pow2 (width - 1) - 1,pow2 (width - 1),
               pow2 (width - 1) + 1,pow2 width - 2,pow2 width - 1]
    pairs = Set.fromList ([(x,y) | x <- values, y <- anchors] ++
                          [(x,y) | x <- anchors, y <- values] ++
                          [(x,y) | x <- values, y <- [x-1,x,x+1], y >= 0, y < pow2 width])
operands SignedNarrow e
  | entryArity e == 1 = [(x,0) | x <- Set.toAscList $ Set.union
      (Set.fromList values) (if width == 8 then Set.fromList [-128 .. 127] else Set.empty)]
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

run :: FilePath -> [(String,String)] -> FilePath -> [String] -> String -> IO String
run root overrides program args input = do
  environment <- getEnvironment
  let updated = foldr (uncurry replace) environment overrides
      replace key value rest = (key,value) : filter ((/= key) . fst) rest
      command = (proc program args) {cwd = Just root, env = Just updated}
  (code,stdout,stderr) <- readCreateProcessWithExitCode command input
  case code of
    ExitSuccess -> pure stdout
    ExitFailure n -> die (unlines [program ++ " failed (" ++ show n ++ ")",
                             unwords args, stdout, stderr])

writeJson :: FilePath -> Value -> IO ()
writeJson path value = BL.writeFile path (encode value <> "\n")

hashFile :: FilePath -> IO String
hashFile path = do
  digest <- SHA256.hash <$> BS.readFile path
  pure $ concatMap hexByte (BS.unpack digest)
  where hexByte byte = let digits = showHex byte "" in replicate (2 - length digits) '0' ++ digits

hashes :: FilePath -> [FilePath] -> IO (Map.Map String String)
hashes root paths = Map.fromList <$> forM paths (\path -> do
  digest <- hashFile (root </> path)
  pure (path,digest))

relativeCore :: Family -> String -> [FilePath]
relativeCore family stage =
  let dir = "build" </> familyName family </> stage
  in if family == Explicit64 then [dir </> fixtureModule family ++ ".json"] else
       [dir </> fixtureModule family ++ ".json", dir </> "THC.InterfaceClosure.json"]

auditRoots :: FilePath -> Family -> [Entry] -> String -> [FilePath] -> IO [FilePath]
auditRoots root family es stage modules = do
  let names = maybe [] (:[]) (compositeName family) ++ map entryName es
      directory = "build" </> familyName family
      auditPath name = directory </> (if family == Bit then stage ++ "-" else "") ++
        (if Just name == compositeName family then "composite" else name) ++ ".audit.json"
  forM names $ \name -> do
    let path = auditPath name
    _ <- run root [] "python3" (["scripts/audit-core.py", "--entry", name,
                                "--output", path] ++ modules) ""
    pure path

inputPaths :: FilePath -> Family -> IO [FilePath]
inputPaths root family = do
  plugin <- listDirectory (root </> "compiler/THC")
  let source = "compiler/test-fixtures" </> fixtureModule family ++ ".hs"
  pure $ sort $ [source, "thc.cabal", "test/haskell-fixtures/Main.hs",
    "scripts/audit-core.py", "scripts/core-capabilities.json",
    "src/main/resources/thc/scalar-primop-signatures.json",
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
    reports <- auditRoots root family es (if stage == "pre-core" then "pre" else "post") modules
    pure (stage,modules,reports)
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
  actual <- run root [] (root </> executable) [] stdinText
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
  let modules = concat [paths | (_,paths,_) <- stageArtifacts]
      reports = concat [paths | (_,_,paths) <- stageArtifacts]
      artifacts = modules ++ reports ++ [driver,oracle]
  sourceHashes <- hashes root sources
  artifactHashes <- hashes root artifacts
  ghcInfo <- if family == Explicit64 then run root [] ghc ["--info"] "" else pure ""
  let common = ["schema" .= (1 :: Int), "entries" .= map (entryJson family) es,
                "inputHashes" .= sourceHashes, "artifactHashes" .= artifactHashes]
      details = case family of
        Bit -> ["ghc" .= ("9.14.1" :: String),
          "stages" .= case stageArtifacts of
            [(_,pre,_),(_,post,_)] -> object ["pre" .= pre, "post" .= post]
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
            show (length parsed) ++ " native rows, " ++ show (length reports) ++ " strict Core audits")

splitTab :: String -> [String]
splitTab text = case break (== '\t') text of
  (piece,[]) -> [piece]
  (piece,_:rest) -> piece : splitTab rest

readInteger :: String -> Maybe Integer
readInteger text = case reads text of
  [(number,"")] -> Just number
  _ -> Nothing

main :: IO ()
main = do
  args <- getArgs
  family <- case args of
    ["bit"] -> pure Bit
    ["integer"] -> pure IntegerWord
    ["signed-narrow"] -> pure SignedNarrow
    ["explicit64"] -> pure Explicit64
    _ -> die "Usage: thc-fixtures (bit|integer|signed-narrow|explicit64)"
  root <- getCurrentDirectory
  exists <- doesFileExist (root </> "thc.cabal")
  unless exists (die "Run thc-fixtures from the THC repository root")
  prepare root family
