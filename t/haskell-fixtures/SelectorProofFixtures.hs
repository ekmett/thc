-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}

-- | Check representation certificates on genuine exported selector templates.
module SelectorProofFixtures (prepareSelectorProof) where

import Control.Monad (forM, unless)
import Data.Aeson (Value(..), object, (.=))
import Data.Aeson.Key (Key)
import qualified Data.Aeson.KeyMap as KeyMap
import qualified Data.ByteString.Char8 as BSC
import Data.Foldable (toList)
import Data.List (sort)
import Data.Text (Text)
import FixtureSupport
import System.Directory (createDirectoryIfMissing, listDirectory)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>), takeExtension)
import THC.Compact.JSON (parseModuleWithoutDebug)
import THC.Compact.Module (readModuleValue, writeModule)
import THC.Compact.Inspect (inspectContainer)

prepareSelectorProof :: FilePath -> IO ()
prepareSelectorProof root = do
  let directory = "build/selector-proof"
      source = "t/fixtures/compiler/SelectorProofAudit.hs"
      predicate = "t/fixtures/compiler/SelectorProofPredicate.hs"
  createDirectoryIfMissing True (root </> directory)
  artifacts <- forM ["pre", "post"] $ \stage -> do
    let core = directory </> stage </> "core"
        path = core </> "SelectorProofAudit.cbd"
    exported <- runLogged 300 root (directory </> "commands") (stage ++ "-export")
      [("THC_CORE_OUT", root </> core), ("THC_GHC_OUT", root </> directory </> stage </> "ghc")]
      "bin/export-core.sh" (["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"] ++ [source])
    value <- BSC.readFile (root </> path) >>= either die pure . readModuleValue
    let bindings = array (field "bindings" value)
        entry name = case filter ((== String ("main:SelectorProofAudit." <> name)) . field "id") bindings of
          [binding] -> pure (field "expr" binding)
          _ -> die ("Missing genuine selector fixture entry " ++ show name)
    method <- entry "method"
    superclass <- entry "superclass"
    unary <- entry "unary"
    implicitSupply <- entry "implicitSupply"
    -- In a non-unary template the dictionary is DATA, each method is a lazy
    -- closure, and the case forces only its dictionary, not the selected field.
    checkTemplate "method" "closure" ["data", "closure"] method
    checkTemplate "superclass" "data" ["data", "closure"] superclass
    checkTemplate "superclass method" "closure" ["closure", "closure"] superclass
    let erased = [parameter | node <- descendants unary, tagged "lam" node,
                             parameter <- array (at 1 node)]
    check (any (unknown . field "rep") erased)
      "Unary selector erasure unexpectedly gained a binder certificate"
    checkImplicitCase implicitSupply
    -- The same distinction must survive the existing compact metadata codec.
    (facts, records) <- either die pure (parseModuleWithoutDebug value)
    let compact = directory </> stage </> "SelectorProofAudit.roundtrip.cbd"
    _ <- writeModule (root </> compact) facts records
    decoded <- BSC.readFile (root </> compact) >>= either die pure . inspectContainer
    let decodedBindings = array (field "bindings" decoded)
        originals = map (field "expr") bindings
        restored = map (field "expr") decodedBindings
        caseProofs expressions = [(field "rep" (at 4 node), field "resultRep" (at 4 node)) |
          expression <- expressions, node <- descendants expression, tagged "case" node]
    check (caseProofs originals == caseProofs restored)
      "Compact roundtrip changed intrinsic or enclosing case certificates"
    putStrLn ("PASS " ++ stage ++ " selector and implicit-parameter case proofs, unary exclusion, compact roundtrip")
    pure (path : compact : commandArtifacts exported)
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  let api = directory </> "api"
  createDirectoryIfMissing True (root </> api)
  built <- runLogged 180 root (directory </> "commands") "predicate-build" [] ghc
    ["--make", "-O0", "-dynamic", "-package", "ghc", "-isrc/compiler", "-odir", api, "-hidir", api,
     predicate, "-o", api </> "predicate"]
  libdir <- runLogged 30 root (directory </> "commands") "libdir" [] ghc ["--print-libdir"]
  path <- case lines (BSC.unpack (commandStdout libdir)) of
    [one] -> pure one
    _ -> die "Expected one GHC libdir"
  checked <- runLogged 60 root (directory </> "commands") "predicate-run" [] (root </> api </> "predicate")
    [path, source]
  BSC.putStr (commandStdout checked)
  compiler <- listDirectory (root </> "src/compiler/THC")
  codec <- listDirectory (root </> "src/cbd/THC/Compact")
  inputHashes <- hashes root $ sort $ [source, predicate, "t/haskell-fixtures/SelectorProofFixtures.hs",
    "t/haskell-fixtures/Main.hs", "t/haskell-fixtures/FixtureSupport.hs",
    "bin/export-core.sh", "bin/build-compiler.sh", "bin/toolchain.sh", "bin/plugin.py", "thc.cabal"] ++
    ["src/compiler/THC" </> name | name <- compiler, takeExtension name == ".hs"] ++
    ["src/cbd/THC/Compact" </> name | name <- codec, takeExtension name == ".hs"]
  artifactHashes <- hashes root (concat artifacts ++ concatMap commandArtifacts [built, libdir, checked])
  writeJson (root </> directory </> "manifest.json") $ object
    ["schema" .= (1 :: Int), "inputHashes" .= inputHashes, "artifactHashes" .= artifactHashes]
  where
    checkTemplate label resultKind fieldKinds expression = do
      let templates = [node | node <- descendants expression, tagged "lam" node,
                             tagged "case" (at 2 node)]
          matching node = field "kind" (field "resultRep" (at 3 node)) == String resultKind
      template <- case filter matching templates of
        [node] -> pure node
        _ -> die (label ++ ": missing certified selector template")
      let parameters = array (at 1 template)
          body = at 2 template
          alternatives = array (at 3 body)
      check (length parameters == 1 && all (boxed "data" False . field "rep") parameters)
        (label ++ ": original lazy dictionary input lost its proof")
      check (boxed "data" True (field "rep" (field "binder" (at 4 body))))
        (label ++ ": evaluated case dictionary lost its proof")
      check (boxed resultKind False (field "rep" (at 4 body)))
        (label ++ ": case result lost its exact lazy carrier")
      case alternatives of
        [alternative] -> do
          let fields = array (field "binders" (at 4 alternative))
          check (length fields == length fieldKinds && and
            (zipWith (\kind b -> boxed kind False (field "rep" b)) fieldKinds fields))
            (label ++ ": selector field types/laziness changed")
          check (boxed resultKind False (field "rep" (lastValue (at 3 alternative))))
            (label ++ ": selected result lost its exact lazy carrier")
        _ -> die (label ++ ": selector is not one constructor alternative")

    checkImplicitCase expression = do
      let cases = [node | node <- descendants expression, tagged "case" node,
                         unknown (field "rep" (at 4 node))]
      node <- case cases of
        [one] -> pure one
        _ -> die "Expected one genuinely erased C:IP case result"
      let result = field "resultRep" (at 4 node)
      check (boxed "data" False result)
        "Implicit-parameter erasure lost the intrinsic case result certificate"
      check (length (array (at 3 node)) == 2 && all
        (boxed "data" True . field "rep" . lastValue . at 3) (array (at 3 node)))
        "Implicit-parameter case alternatives lost their independent payload proofs"

check :: Bool -> String -> IO ()
check condition message = unless condition (die message)

field :: Key -> Value -> Value
field key (Object fields) = maybe Null id (KeyMap.lookup key fields)
field _ _ = Null
array :: Value -> [Value]
array (Array values) = toList values
array _ = []
at :: Int -> Value -> Value
at index value = case drop index (array value) of x:_ -> x; _ -> Null
lastValue :: Value -> Value
lastValue value = case reverse (array value) of x:_ -> x; _ -> Null
tagged :: Text -> Value -> Bool
tagged tag value = at 0 value == String tag
unknown :: Value -> Bool
unknown rep = field "kind" rep == String "unknown" && field "primReps" rep == Null
boxed :: Text -> Bool -> Value -> Bool
boxed kind evaluated rep = field "kind" rep == String kind &&
  field "evaluated" rep == Bool evaluated && array (field "primReps" rep) == [String "BoxedRep (Just Lifted)"]
descendants :: Value -> [Value]
descendants value = value : case value of
  Array values -> concatMap descendants (toList values)
  Object fields -> concatMap descendants (KeyMap.elems fields)
  _ -> []
