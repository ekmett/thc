-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}
module UnsafeEqualityFixtures (prepareUnsafeEquality) where

import Control.Monad (forM, forM_, unless, when)
import Data.Aeson (Value(..), eitherDecodeFileStrict, object, toJSON, (.=))
import qualified Data.Aeson.Key as Key
import qualified Data.Aeson.KeyMap as KeyMap
import Data.Foldable (toList)
import Data.Int (Int64)
import Data.List (isPrefixOf, sort)
import Data.Maybe (fromMaybe)
import qualified Data.Text as Text
import Data.Time.Clock (getCurrentTime)
import FixtureSupport (hashFile, readInteger, run, splitTab, writeJson)
import System.Directory (createDirectoryIfMissing, doesDirectoryExist, doesFileExist,
                         listDirectory, removeFile)
import System.Environment (lookupEnv)
import System.Exit (ExitCode(..), die)
import System.FilePath ((</>), takeExtension)
import System.Process (CreateProcess(cwd), proc, readCreateProcessWithExitCode)

entries, frontiers, effects :: [String]
entries = ["primitiveCase", "liftedCase", "tupleCase", "lazyCase", "unusedCase", "unusedBottomCase", "nestedCase"]
frontiers = ["firstClassProof", "liveBinder"]
effects = ["wrongCalleeCase", "demandedBottomCase"]

proof :: Value
proof = String "ghc-internal:GHC.Internal.Unsafe.Coerce.unsafeEqualityProof"

directory :: FilePath
directory = "build/unsafe-equality"

stages :: [(String, String)]
stages = [("pre", "optimized-Core-before-Tidy"), ("post", "optimized-Core-after-Tidy-before-CorePrep")]

source, driver, predicateSource :: FilePath
source = "compiler/test-fixtures/UnsafeEqualityAudit.hs"
driver = "compiler/test-fixtures/UnsafeEqualityAuditNative.hs"
predicateSource = "compiler/test-fixtures/UnsafeEqualityPredicate.hs"

fixtures :: [FilePath]
fixtures = [source, driver, predicateSource]

check :: Bool -> String -> IO ()
check condition message = unless condition (die message)

readJson :: FilePath -> IO Value
readJson path = either (die . ((path ++ ": ") ++)) pure =<< eitherDecodeFileStrict path

field :: Key.Key -> Value -> Value
field key (Object fields) = fromMaybe Null (KeyMap.lookup key fields)
field _ _ = Null

values :: Value -> [Value]
values (Array xs) = toList xs
values _ = []

walk :: Value -> [Value]
walk value = value : concatMap walk (case value of Object fields -> KeyMap.elems fields; _ -> values value)

variable :: Value -> Value -> Bool
variable name value = take 2 (values value) == [String "var", name]

inventory :: FilePath -> (String, String) -> IO Value
inventory root (stage, boundary) = do
  original <- readJson (root </> directory </> stage ++ "-core/UnsafeEqualityAudit.json")
  closure <- readJson (root </> directory </> stage ++ "-core/THC.InterfaceClosure.json")
  check (field "ghc" original == String "9.14.1" && field "boundary" original == toJSON boundary) "Wrong Core boundary"
  let bindings = values (field "bindings" original) ++ values (field "bindings" closure)
      expression name = fromMaybe Null $ lookup (String name)
        (reverse [(field "name" binding, field "expr" binding) | binding <- bindings])
      hasProof = any (variable proof) . walk
  proofs <- forM [("primitive", "long"), ("nested", "long"), ("tuple", "unknown"),
                  ("lazyValue", "data"), ("unsafeCoerce", "object")] $ \(name, kind) -> do
    mark <- case [node | node@(Object fields) <- walk (expression name), KeyMap.member "unsafeEqualityCase" fields] of
      [node] -> pure node
      _ -> die (stage ++ "/" ++ Text.unpack name ++ ": genuine late case must be lowered exactly once")
    let representation = field "rep" mark
        notes = values (field "sourceNotes" mark)
    check (field "unsafeEqualityCase" mark == String "GHC.Core.Utils.isUnsafeEqualityCase/CoreToStg" &&
           field "kind" representation == String kind) "Wrong unsafe-equality lowering or representation"
    check (not (hasProof (expression name))) "Lowered case still depends on proof"
    unless (name == "unsafeCoerce") $ check (not (null notes) &&
      all (`elem` map (field "id") (values (field "sourceSpans" original))) notes) "Lost unsafe-equality source notes"
    pure (name, representation)
  let result name = fromMaybe Null (lookup name proofs)
  check (field "primReps" (result "primitive") == toJSON (["IntRep"] :: [String])) "Unlifted result lost exact representation"
  check (field "aggregate" (result "tuple") == String "unboxed-tuple" &&
         field "primReps" (result "tuple") == toJSON (["IntRep", "BoxedRep (Just Lifted)"] :: [String])) "Tuple result proof lost"
  check (all ((== Bool False) . field "evaluated" . result) ["lazyValue", "unsafeCoerce"])
    "Lowering must not mark arbitrary lifted payload evaluated"
  check (all (\value -> case field "sourceCore" value of
    String text -> "unsafeEqualityProof" `Text.isInfixOf` text; _ -> False) [original, closure]) "Original Core proof evidence disappeared"
  forM_ frontiers $ \name -> check (hasProof (expression (Text.pack name))) (name ++ ": nonmatching proof dependency disappeared")
  case [values node | node@(Array _) <- walk (expression "liveBinder"), take 1 (values node) == [String "case"]] of
    [[_, scrutinee, binder, alternatives, metadata]] -> check (variable proof scrutinee &&
      field "occurrence" (field "info" (field "binder" metadata)) `notElem` [Null, String "Dead"] &&
      any (variable binder) (walk alternatives)) "Live case binder must remain used and unlowered"
    _ -> die "Expected one genuine live case"
  check (not (or [KeyMap.member "unsafeEqualityCase" fields | Object fields <- walk (expression "wrongCalleeCase")]))
    "Arbitrary proof-producing call must not be erased"
  check (any (\binding -> field "origin" binding == String "interface-core-unfolding" &&
    field "name" binding == String "unsafeCoerce") (values (field "bindings" closure))) "Expected actual installed unsafeCoerce unfolding"
  pure $ object ["stage" .= stage, "resultProofs" .= object [Key.fromText name .= value | (name, value) <- proofs],
                 "nonmatchingFrontiers" .= frontiers]

sourcePaths :: FilePath -> IO [FilePath]
sourcePaths root = do
  plugins <- listDirectory (root </> "compiler/THC")
  scripts <- listDirectory (root </> "scripts")
  pure $ sort $ fixtures ++ ["test/haskell-fixtures/UnsafeEqualityFixtures.hs", "test/haskell-fixtures/FixtureSupport.hs",
    "test/haskell-fixtures/Main.hs", "thc.cabal", "compiler/build.sh", "compiler/export.sh", "compiler/toolchain.sh",
    "scripts/audit-core.py", "scripts/core-capabilities.json", "src/main/resources/thc/scalar-primop-signatures.json",
    "scripts/generate-scalar-signatures.py"] ++
    ["compiler/THC" </> file | file <- plugins, takeExtension file == ".hs"] ++
    ["scripts" </> file | file <- scripts, "core_" `isPrefixOf` file, takeExtension file == ".py"]

record :: FilePath -> FilePath -> IO Value
record root path = do
  digest <- hashFile (root </> path)
  pure $ object ["path" .= path, "sha256" .= digest]

filesBelow :: FilePath -> FilePath -> IO [FilePath]
filesBelow root folder = do
  names <- sort <$> listDirectory (root </> folder)
  fmap concat $ forM names $ \name -> do
    let path = folder </> name
    nested <- doesDirectoryExist (root </> path)
    if nested then filesBelow root path else pure [path]

checkRows :: FilePath -> IO Int
checkRows root = do
  raw <- lines <$> readFile (root </> directory </> "oracle.tsv")
  let parse row = case splitTab row of
        [name, x, y] -> (,,) name <$> readInteger x <*> readInteger y
        _ -> Nothing
      inputs = [minBound, -4097, -1, 0, 1, 4097, maxBound] :: [Int64]
      model name x = case name of
        "tupleCase" -> 2*x + 3
        "unusedCase" -> x + if x < 0 then 7 else 17
        _ -> x + fromMaybe (error "Unknown native model entry")
          (lookup name [("primitiveCase",5), ("liftedCase",11), ("lazyCase",-13), ("unusedBottomCase",19), ("nestedCase",-23)])
      expected = sort [(name, toInteger x, toInteger (model name x)) | name <- entries, x <- inputs]
  check (fmap sort (traverse parse raw) == Just expected) "Native results disagree with independent Int64 model"
  pure (length raw)

prepareUnsafeEquality :: FilePath -> Bool -> IO ()
prepareUnsafeEquality root checkOnly = do
  let output = root </> directory
      provenance = directory </> "provenance.json"
      checks = output </> "checks.json"
      command :: [(String,String)] -> FilePath -> [String] -> Value
      command environment program arguments = object ["argv" .= (program : arguments), "environment" .= object
        [Key.fromString key .= value | (key,value) <- environment]]
      execute environment program arguments = do
        result <- run root environment program arguments ""
        pure (result, command environment program arguments)
  evidence <- if checkOnly then readJson (root </> provenance) else do
    createDirectoryIfMissing True output
    forM_ [root </> provenance, checks] $ \path -> do
      present <- doesFileExist path
      when present (removeFile path)
    ghc <- maybe "ghc" id <$> lookupEnv "GHC"
    version <- run root [] ghc ["--numeric-version"] ""
    check (lines version == ["9.14.1"]) "Requires pinned GHC9.14.1"
    forM_ ["native", "api"] (createDirectoryIfMissing True . (output </>))
    (_, build) <- execute [] "compiler/build.sh" []
    (_, predicateBuild) <- execute [] ghc ["--make", "-v0", "-O0", "-dynamic", "-package", "ghc", "-icompiler",
      "-odir", output </> "api", "-hidir", output </> "api", predicateSource, "-o", output </> "api/predicate"]
    libdirs <- lines <$> run root [] ghc ["--print-libdir"] ""
    libdir <- case libdirs of [path] -> pure path; _ -> die "Expected one selected GHC libdir"
    (predicateOutput, predicateRun) <- execute [] (output </> "api/predicate") [libdir]
    putStr predicateOutput
    (_, nativeBuild) <- execute [] ghc ["--make", "-O2", "-fforce-recomp", "-dcore-lint", "-icompiler/test-fixtures",
      "-odir", output </> "native", "-hidir", output </> "native", "-o", output </> "native/oracle", driver]
    (oracle, nativeRun) <- execute [] (output </> "native/oracle") []
    writeFile (output </> "oracle.tsv") oracle
    stageCommands <- forM stages $ \(stage, _) -> do
      let core = directory </> stage ++ "-core"
          exportEnvironment = [("THC_CORE_OUT", root </> core), ("THC_GHC_OUT", output </> stage ++ "-ghc"), ("THC_SOURCE_NOTES", "true")]
          auditArguments names path = ["scripts/audit-core.py", core, "--output", directory </> path] ++ concatMap (\name -> ["--entry",name]) names
      (_, exported) <- execute exportEnvironment "compiler/export.sh" (["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"] ++
        map ("-fplugin-opt=THC.Plugin:closure=" ++) (entries ++ frontiers ++ effects) ++ [source])
      (_, audited) <- execute [] "python3" (auditArguments (entries ++ effects) (stage ++ "-audit.json"))
      negative <- forM frontiers $ \name -> do
        let path = stage ++ "-" ++ name ++ "-audit.json"
            arguments = auditArguments [name] path
        (code, stdout, stderr) <- readCreateProcessWithExitCode ((proc "python3" arguments) {cwd = Just root}) ""
        check (code == ExitFailure 1) ("Expected rejected proof frontier: " ++ show code ++ stdout ++ stderr)
        report <- readJson (output </> path)
        check (field "accepted" report == Bool False && map (field "id") (values (field "missingGlobals" report)) == [proof] &&
          field "issues" report == Array mempty) (stage ++ "/" ++ name ++ ": expected exact unresolved proof frontier")
        pure (command [] "python3" arguments)
      pure (exported : audited : negative)
    sources <- sourcePaths root >>= mapM (record root)
    retained <- concat <$> mapM (filesBelow root . (directory </>)) ["native", "api", "pre-core", "post-core"]
    reports <- filter (\name -> "-audit.json" `Text.isSuffixOf` Text.pack name) <$> listDirectory output
    artifacts <- mapM (record root) (retained ++ map (directory </>) ("oracle.tsv" : sort reports))
    now <- getCurrentTime
    ghcInfo <- run root [] ghc ["--info"] ""
    pure $ object ["schema" .= (1 :: Int), "recordedAtUtc" .= now,
      "commands" .= ([build, predicateBuild, predicateRun, nativeBuild, nativeRun] ++ concat stageCommands),
      "ghcInfo" .= ghcInfo, "sources" .= sources, "artifacts" .= artifacts,
      "claim" .= ("Original native Haskell and actual GHC API predicate; only matching late cases are lowered. Runtime compilation checked separately." :: String)]
  currentSources <- sourcePaths root
  check (sort (map (field "path") (values (field "sources" evidence))) == sort (map toJSON currentSources)) "Missing current unsafe-equality source hashes"
  forM_ (values (field "sources" evidence) ++ values (field "artifacts" evidence)) $ \item -> case field "path" item of
    String path -> do
      actual <- record root (Text.unpack path)
      check (actual == item) ("Stale unsafe-equality fixture: " ++ Text.unpack path)
    _ -> die "Invalid unsafe-equality provenance path"
  count <- checkRows root
  coverage <- mapM (inventory root) stages
  unless checkOnly (writeJson (root </> provenance) evidence)
  provenanceRecord <- record root provenance
  writeJson checks $ object ["schema" .= (1 :: Int), "nativeRows" .= count,
    "coverage" .= coverage, "provenance" .= provenanceRecord]
  putStrLn ("Unsafe equality: " ++ show count ++ " native/model rows, exact GHC predicate controls, 9 strict roots and 2 explicit frontiers at both stages")
