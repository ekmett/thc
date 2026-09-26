-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE OverloadedStrings #-}
module BigNatLiteralFixtures (prepareBigNatLiterals) where

import Control.Monad (forM, forM_, unless, when)
import Data.Aeson (FromJSON, Value(..), eitherDecodeStrict', fromJSON, Result(..), object, toJSON, (.=))
import qualified Data.Aeson.KeyMap as KeyMap
import Data.Bits ((.&.), shiftR)
import qualified Data.ByteString as BS
import Data.Foldable (toList)
import Data.List (isPrefixOf, nub, sort)
import qualified Data.Map.Strict as Map
import qualified Data.Set as Set
import Data.String (fromString)
import Data.Word (Word8, Word16)
import FixtureSupport (CommandResult(..), hashes, run, runLogged, runLoggedExpect, runLoggedWithInput, writeJson)
import Foreign (Ptr, alloca, castPtr, peek, poke)
import System.Directory (createDirectoryIfMissing, doesDirectoryExist, doesFileExist, listDirectory, removeFile)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>), takeExtension)
import Text.Read (readMaybe)

entries, arithmetic, modules, originals, vendorSources :: [String]
entries = ["integerRoundTrip","naturalRoundTrip","integerLiteral","naturalLiteral",
  "magnitudeSize","magnitudeByte","magnitudeWord","magnitudeSign"]
arithmetic = ["integerAddFrontier","naturalAddFrontier"]
modules = ["BigNat","Integer","Natural"]
originals = [directory </> "boot/core/GHC.Internal.Bignum." ++ name ++ ".json" | name <- modules]
vendorSources = ["vendor/ghc-9.14.1/GHC/Internal/Bignum" </> name ++ suffix |
  name <- modules, suffix <- [".hs",".hs-boot"]] ++
  ["vendor/ghc-9.14.1/include/WordSize.h","vendor/ghc-9.14.1/LICENSE"]

directory, logs, prefix :: String
directory = "build/bignat-literals"
logs = directory </> "commands"
prefix = "ghc-internal:GHC.Internal.Bignum."

values, seeds :: [Integer]
values = [0,1,-1,power 63-1,power 63,power 64-1,power 64,power 64+1,power 127-1,power 127,power 128,
  power 128+power 63+1,-(power 128+power 64-1),power 192-1,power 192+1,power 255+power 128+3]
seeds = [-power 63,power 63-1,-1000,-17,-1] ++ [0..16] ++ [31,power 32]

power :: Int -> Integer
power n = 2^n

size :: Integer -> Int
size number = go (abs number) where go 0 = 0; go n = 8 + go (n `shiftR` 64)

requests :: [(String,Integer,Int)]
requests = [(name,seed,index) | name <- entries, seed <- seeds,
  let bytes = size (values !! fromInteger (seed .&. 15)),
  index <- case name of "magnitudeByte" -> [-1..bytes]; "magnitudeWord" -> [-1..bytes `div` 8]; _ -> [0]]

expected :: String -> String -> Integer -> Int -> Integer
expected order name seed index = case name of
  "integerRoundTrip" -> seed
  "naturalRoundTrip" -> seed
  "integerLiteral" -> signed number
  "naturalLiteral" -> signed magnitude
  "magnitudeSize" -> toInteger bytes
  "magnitudeSign" -> if number < 0 then 1 else 0
  "magnitudeWord" -> if index < 0 || index >= bytes `div` 8 then -1 else signed (magnitude `shiftR` (64*index))
  "magnitudeByte" -> if index < 0 || index >= bytes then -1 else
    (magnitude `shiftR` (8*(if order == "little" then index else index `div` 8*8+7-index `mod` 8))) .&. 255
  _ -> error "Unknown BigNat entry"
  where
    number = values !! fromInteger (seed .&. 15)
    magnitude = abs number
    bytes = size magnitude
    signed n = let word = n .&. (2^(64 :: Int)-1) in if word >= 2^(63 :: Int) then word-2^(64 :: Int) else word

field :: FromJSON a => String -> Value -> IO a
field key (Object fields) = case KeyMap.lookup (fromString key) fields of
  Just value -> case fromJSON value of Success result -> pure result; Error message -> die message
  Nothing -> die ("Missing BigNat field: " ++ key)
field key _ = die ("Expected BigNat object for " ++ key)

readJson :: FilePath -> IO Value
readJson path = BS.readFile path >>= either die pure . eitherDecodeStrict'

walk :: Value -> [Value]
walk value = value : concatMap walk (case value of Object fields -> toList fields; Array items -> toList items; _ -> [])

check :: Bool -> String -> IO ()
check condition message = unless condition (die message)

tree :: FilePath -> FilePath -> IO [FilePath]
tree root path = do
  names <- sort <$> listDirectory (root </> path)
  concat <$> forM names (\name -> do
    let child = path </> name
    isDirectory <- doesDirectoryExist (root </> child)
    if isDirectory then tree root child else pure [child])

sourcePaths :: FilePath -> IO [FilePath]
sourcePaths root = do
  plugins <- listDirectory (root </> "compiler/THC")
  scripts <- listDirectory (root </> "scripts")
  pure $ sort $ vendorSources ++
    ["compiler/test-fixtures/BigNatLiteralAudit.hs","compiler/test-fixtures/BigNatLiteralAuditNative.hs",
     "test/haskell-fixtures/BigNatLiteralFixtures.hs","test/haskell-fixtures/FixtureSupport.hs",
     "test/haskell-fixtures/Main.hs","thc.cabal","compiler/export-boot.py","compiler/build.sh",
     "compiler/export.sh","compiler/toolchain.sh","compiler/plugin.py","scripts/audit-core.py",
     "scripts/core-capabilities.json","scripts/generate-scalar-signatures.py",
     "src/main/resources/thc/scalar-primop-signatures.json"] ++
    ["compiler/THC" </> name | name <- plugins, takeExtension name == ".hs"] ++
    ["scripts" </> name | name <- scripts, "core_" `isPrefixOf` name, takeExtension name == ".py"]

records :: FilePath -> [FilePath] -> IO [Value]
records root paths = map (\(path,digest) -> object ["path" .= path,"sha256" .= digest]) . Map.toAscList <$> hashes root paths

verifyBootSources :: FilePath -> IO ()
verifyBootSources root = do
  boot <- readJson (root </> directory </> "boot/boot-provenance.json")
  sourceRecords <- field "sources" boot :: IO [Value]
  paths <- mapM (field "path") sourceRecords
  check (sort paths == sort vendorSources) "Changed original BigNat source provenance"
  forM_ sourceRecords $ \record -> do
    path <- field "path" record
    digest <- field "sha256" record
    actual <- hashes root [path]
    check (Map.lookup path actual == Just digest) "Stale original BigNat source provenance"

-- Re-run the existing auditor CLI on check-only too. Its proof implementation
-- remains shared; no dynamic Python imports or replacement capability checker.
inventory :: FilePath -> IO (Value,Value,Value)
inventory root = do
  original <- mapM (readJson . (root </>)) originals
  forM_ original $ \core -> do
    ghc <- field "ghc" core :: IO String
    boundary <- field "boundary" core :: IO String
    sourceCore <- field "sourceCore" core :: IO String
    spans <- field "sourceSpans" core :: IO [Value]
    check (ghc == "9.14.1" && boundary == "optimized-Core-after-Tidy-before-CorePrep" &&
      not (null sourceCore) && not (null spans))
      "Complete original source evidence required"
  originalBindings <- mapM (field "bindings") original :: IO [[Value]]
  sourceIds <- Set.fromList <$> mapM (field "id") (concat originalBindings) :: IO (Set.Set String)
  stages <- forM ["pre","post"] $ \stage -> do
    let publicPath = directory </> stage ++ "-core/BigNatLiteralAudit.json"
        closurePath = directory </> stage ++ "-core/THC.InterfaceClosure.json"
        paths = publicPath:originals
    public <- readJson (root </> publicPath)
    ghc <- field "ghc" public :: IO String
    boundary <- field "boundary" public :: IO String
    check (ghc == "9.14.1" && boundary == if stage == "pre" then "optimized-Core-before-Tidy"
      else "optimized-Core-after-Tidy-before-CorePrep") "Wrong public export boundary"
    closure <- readJson (root </> closurePath) >>= field "bindings" :: IO [Value]
    closureIds <- Set.fromList <$> mapM (field "id") closure
    check (closureIds `Set.isSubsetOf` sourceIds) "Original source must replace complete interface closure"
    bindings <- (++) (concat originalBindings) <$> field "bindings" public
    indexed <- Map.fromList <$> forM bindings (\binding -> do ident <- field "id" binding; pure (ident :: String,binding))
    reports <- forM (entries ++ arithmetic) $ \name -> do
      report <- audit stage name paths [name] (if name == "integerAddFrontier" then 1 else 0)
      accepted <- field "accepted" report :: IO Bool
      issues <- field "issues" report :: IO [Value]
      missing <- field "missingGlobals" report :: IO [Value]
      missingIds <- mapM (field "id") missing :: IO [String]
      reached <- field "reachableBindings" report :: IO [Value]
      ids <- mapM (field "id") reached :: IO [String]
      check (null issues) "Unexpected BigNat audit issues"
      if name `elem` entries then do
        let worker | "integer" `isPrefixOf` name = "Integer.integerToInt#"
                   | "natural" `isPrefixOf` name = "Natural.naturalToWord#"
                   | otherwise = "Integer.integerToBigNatSign#"
        check (accepted && null missing && prefix ++ worker `elem` ids) "Original conversion worker disappeared"
        bodies <- forM ids $ \ident -> maybe (die "Audit reached a missing binding") (field "expr") (Map.lookup ident indexed)
        let code = [toList items | body <- bodies, Array items <- walk body]
            has tag ident = any ((== [String tag,String (fromString ident)]) . take 2) code
            integer = not ("natural" `isPrefixOf` name)
        if name `elem` ["integerRoundTrip","naturalRoundTrip"] then
          check (has "var" ("main:BigNatLiteralAudit." ++ if integer then "integerIdentity" else "naturalIdentity") &&
            has "con" (prefix ++ if integer then "Integer.IS" else "Natural.NS")) "Opaque roundtrip or small constructor disappeared"
        else do
          let choice = "main:BigNatLiteralAudit." ++ if integer then "integerChoice" else "naturalChoice"
              constructors = if integer then ["Integer.IP","Integer.IN"] else ["Natural.NB"]
              literals = [literal | literal <- code, take 2 literal == [String "lit",String "bignat"]]
          check (choice `elem` ids && has "var" choice && all (has "con" . (prefix ++)) constructors && not (null literals))
            "Opaque literal choice, constructors or BigNat literals disappeared"
          forM_ literals $ \literal -> case drop 3 literal of
            metadata:_ -> do
              proof <- field "rep" metadata :: IO Value
              check (proof == object ["kind" .= ("object" :: String),"evaluated" .= True,
                "primReps" .= ["BoxedRep (Just Unlifted)" :: String]]) "BigNat intrinsic proof changed"
            _ -> die "Missing BigNat intrinsic proof"
      else do
        primitives <- field "primitives" report :: IO [Value]
        shrink <- forM primitives $ \primitive -> do
          label <- field "name" primitive :: IO String
          uses <- field "uses" primitive :: IO [Value]
          pure (if label == "shrinkMutableByteArray#" then length uses else 0)
        calls <- field "foreignCalls" report >>= mapM (field "symbol") :: IO [String]
        let integer = name == "integerAddFrontier"
            wanted = ["ghc-internal:GHC.Internal.Prim.Exception.raiseUnderflow" | integer]
            symbols = ["__gmpn_add","__gmpn_add","__gmpn_add_1"] ++ [symbol | integer, symbol <- ["__gmpn_cmp","__gmpn_sub"]]
        check (sum shrink == (if integer then 7 else 5) && sort calls == sort symbols &&
          missingIds == wanted && accepted == null wanted) "Changed shrink/GMP/exception arithmetic frontier"
      pure (name,object ["accepted" .= accepted,"reachable" .= length reached,"issues" .= length issues,"missing" .= length missing])
    missing <- audit stage "missing-source" [publicPath,closurePath] entries 1
    accepted <- field "accepted" missing :: IO Bool
    issues <- field "issues" missing :: IO [Value]
    ids <- field "missingGlobals" missing >>= mapM (field "id") :: IO [String]
    check (not accepted && null issues && sort ids == sort (map (prefix ++) ["BigNat.bigNatZero","Integer.integerToInt#","Natural.naturalToWord#"]))
      "Changed exact missing-original-source frontier"
    pure (stage,paths,Map.fromList reports)
  pure (object [fromString stage .= paths | (stage,paths,_) <- stages],
    object [fromString stage .= reports | (stage,_,reports) <- stages],
    object [fromString name .= length bindings | (name,bindings) <- zip modules originalBindings])
  where
    audit stage name paths roots exitCode = do
      let reportPath = directory </> stage ++ "-" ++ name ++ ".audit.json"
      _ <- runLoggedExpect exitCode 120 root logs (stage ++ "-" ++ name ++ "-audit") [] "python3"
        (["scripts/audit-core.py"] ++ paths ++ concat [["--entry","main:BigNatLiteralAudit." ++ entry] | entry <- roots] ++ ["--output",reportPath])
      readJson (root </> reportPath)

prepareBigNatLiterals :: FilePath -> Bool -> IO ()
prepareBigNatLiterals root checkOnly = do
  order <- alloca $ \ptr -> do poke ptr (1 :: Word16); byte <- peek (castPtr ptr :: Ptr Word8); pure (if byte == 1 then "little" else "big")
  let manifestPath = root </> directory </> "manifest.json"
      native = directory </> "native"
      binary = native </> "bignat-literal-oracle"
      requestText = unlines [name ++ "\t" ++ show seed ++ "\t" ++ show index | (name,seed,index) <- requests]
      oracleText = unlines [name ++ "\t" ++ show seed ++ "\t" ++ show index ++ "\t" ++ show (expected order name seed index) | (name,seed,index) <- requests]
      verifyFiles = do
        check (length requests == 699 && length (nub requests) == 699) "Changed BigNat corpus"
        actualRequests <- readFile (root </> directory </> "requests.tsv")
        actualOracle <- readFile (root </> directory </> "oracle.tsv")
        check (actualRequests == requestText && actualOracle == oracleText) "Native BigNat corpus/model mismatch"
  unless checkOnly $ do
    createDirectoryIfMissing True (root </> native)
    present <- doesFileExist manifestPath
    when present (removeFile manifestPath)
    ghc <- maybe "ghc" id <$> lookupEnv "GHC"
    version <- run root [] ghc ["--numeric-version"] ""
    check (lines version == ["9.14.1"]) "Pinned GHC 9.14.1 required"
    info <- run root [] ghc ["--info"] ""
    case readMaybe info :: Maybe [(String,String)] of
      Just fields | lookup "target word size" fields == Just "8",
        lookup "target word big endian" fields == Just (if order == "big" then "YES" else "NO") -> pure ()
      _ -> die "BigNat requires native-order 64-bit GHC"
    _ <- runLogged 300 root logs "plugin-build" [] "compiler/build.sh" []
    _ <- runLogged 600 root logs "boot-export" [] "python3"
      ["compiler/export-boot.py","--frontier","bignum","--build-dir",root </> directory </> "boot"]
    forM_ ["pre","post"] $ \stage -> do
      _ <- runLogged 300 root logs (stage ++ "-export")
        [("THC_CORE_OUT",root </> directory </> stage ++ "-core"),("THC_GHC_OUT",root </> directory </> stage ++ "-ghc"),("THC_SOURCE_NOTES","true")]
        "compiler/export.sh" (["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"] ++
          ["-fplugin-opt=THC.Plugin:closure=" ++ name | name <- entries ++ arithmetic] ++ ["compiler/test-fixtures/BigNatLiteralAudit.hs"])
      pure ()
    (stages,coverage,counts) <- inventory root
    _ <- runLogged 300 root logs "native-build" [] ghc
      ["--make","-O2","-fforce-recomp","-dcore-lint","-dstg-lint","-icompiler/test-fixtures",
       "-odir",root </> native,"-hidir",root </> native,"-o",root </> binary,"compiler/test-fixtures/BigNatLiteralAuditNative.hs"]
    writeFile (root </> directory </> "requests.tsv") requestText
    executed <- runLoggedWithInput (directory </> "requests.tsv") 120 root logs "native-oracle" [] (root </> binary) []
    BS.writeFile (root </> directory </> "oracle.tsv") (commandStdout executed)
    verifyFiles
    verifyBootSources root
    sources <- sourcePaths root >>= records root
    artifacts <- artifactPaths >>= records root
    writeJson manifestPath $ object ["schema" .= (1 :: Int),"ghcInfo" .= info,"wordBits" .= (64 :: Int),"byteOrder" .= order,
      "entries" .= entries,"arithmeticControls" .= arithmetic,"frontiers" .= ["integerAddFrontier" :: String],
      "values" .= map show values,"seeds" .= seeds,"nativeRows" .= (699 :: Int),"stages" .= stages,"coverage" .= coverage,
      "sourceBindings" .= counts,"sources" .= sources,"artifacts" .= artifacts,
      "claim" .= ("Fresh native conversion/complete limb and byte observations; complete original BigNat/Integer/Natural source, no arithmetic or foreign substitution. Guest compiled execution is tested separately." :: String)]
  manifest <- readJson manifestPath
  let verifyHashes = do
        sources <- sourcePaths root >>= records root
        artifacts <- artifactPaths >>= records root
        savedSources <- field "sources" manifest
        savedArtifacts <- field "artifacts" manifest
        check (sources == savedSources && artifacts == savedArtifacts) "Stale or incomplete BigNat source/artifact inventory"
  verifyHashes
  verifyFiles
  verifyBootSources root
  (stages,coverage,counts) <- inventory root
  forM_ [("stages",stages),("coverage",coverage),("sourceBindings",counts)] $ \(key,value) -> do
    saved <- field key manifest
    check (saved == value) ("Stale BigNat " ++ key)
  forM_ [("schema",toJSON (1 :: Int)),("entries",toJSON entries),("arithmeticControls",toJSON arithmetic),
    ("frontiers",toJSON ["integerAddFrontier" :: String]),
    ("values",toJSON (map show values)),("seeds",toJSON seeds),
    ("nativeRows",toJSON (699 :: Int)),("wordBits",toJSON (64 :: Int)),("byteOrder",String (fromString order))] $ \(key,value) -> do
    saved <- field key manifest
    check (saved == value) ("Changed BigNat domain: " ++ key)
  verifyHashes
  putStrLn "BigNat: 699 native/model rows, 16 conversion + 4 arithmetic + 2 missing-source audits; complete originals"
  where
    artifactPaths = do
      nested <- concat <$> mapM (tree root . (directory </>)) ["pre-core","post-core","boot/core","native","commands"]
      pure $ sort $ nested ++ [directory </> "boot/boot-provenance.json",directory </> "requests.tsv",directory </> "oracle.tsv"] ++
        [directory </> stage ++ "-" ++ name ++ ".audit.json" | stage <- ["pre","post"], name <- entries ++ arithmetic ++ ["missing-source"]]
