-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : Main
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Native GHC; host filesystem/process services
--
-- Check primop-tool output and reject invalid capability contracts.
module Main (main) where

import Control.Exception (bracket, try)
import Control.Monad (filterM, forM_, unless, when)
import Data.Aeson
import qualified Data.Aeson.KeyMap as KM
import qualified Data.ByteString as BS
import Data.IORef (newIORef)
import Data.Foldable (toList)
import Data.List (isInfixOf)
import Data.Word (Word32)
import GHC.Builtin.Utils (knownKeyNames, wiredInIds, ghcPrimIds)
import GHC.Builtin.PrimOps.Ids (allThePrimOpIds)
import qualified GHC.Builtin.Types as Builtin
import qualified GHC.Builtin.Types.Prim as Prim
import qualified GHC.Plugins as GHC
import GHC.Data.FastMutInt (newFastMutInt)
import GHC.Iface.Binary (BinSymbolTable(..), putName)
import GHC.Types.Unique (mkUnique)
import GHC.Types.Unique.FM (emptyUFM)
import qualified GHC.Utils.Binary as Binary
import qualified Data.Map.Strict as Map
import qualified Data.Text as T
import qualified Data.Text.Encoding as T
import qualified Data.Text.IO as T
import PrimopTools hiding (main)
import System.Directory hiding (executable)
import System.Environment (lookupEnv)
import System.Exit (ExitCode(..), exitFailure)
import System.FilePath ((</>), takeDirectory)
import System.IO (hClose, openTempFile)
import System.Process (CreateProcess(..), proc, readCreateProcessWithExitCode)
import Test.HUnit hiding (counts, path)

rows :: [Row]
rows = [("+#", 2, "Int# -> Int# -> Int#"),
        ("packInt64X2#", 1, "(# Int64#, Int64# #) -> Int64X2#"),
        ("plusAddr#", 2, "Addr# -> Int# -> Addr#"),
        ("tagToEnum#", 1, "forall a. Int# -> a"),
        ("dataToTagSmall#", 1, "forall a. a -> Int#"),
        ("dataToTagLarge#", 1, "forall a. a -> Int#")]

capability, scalars :: Value
capability = object ["primitives" .= object ["+#" .= (2 :: Int), "packInt64X2#" .= (1 :: Int), "plusAddr#" .= (2 :: Int)],
  "tagToEnum" .= ("concrete-nullary-family" :: T.Text), "dataToTag" .= ("concrete-algebraic-family-64" :: T.Text)]
scalars = scalarValue (Map.fromList [("+#", (["IntRep", "IntRep"], "IntRep")),
                                   ("plusAddr#", (["AddrRep", "IntRep"], "AddrRep"))])

ok :: Either String a -> IO a
ok = either (\message -> assertFailure message >> fail message) pure

rejects :: String -> Either String a -> Assertion
rejects expected result = case result of
  Left message -> assertBool message (expected `isInfixOf` message)
  Right _ -> assertFailure ("Expected rejection containing " ++ expected)

derive :: Value -> Value -> Either String Value
derive cap signatures = declaredPrimitives cap >>= report rows >>= \inventory -> classify inventory cap signatures

delete :: Key -> Value -> Value
delete key (Object values) = Object (KM.delete key values)
delete _ value = value

modifyPrimitives :: (Value -> Value) -> Value -> Value
modifyPrimitives f value = case field "primitives" value of
  Right entries -> setFields value [("primitives", f entries)]
  Left message -> error message

byName :: Value -> IO (Map.Map T.Text Value)
byName value = do
  primitives <- ok (field "primitives" value :: Either String [Value])
  Map.fromList <$> traverse (\row -> (,) <$> ok (field "name" row) <*> pure row) primitives

count :: T.Text -> Value -> Either String Int
count key value = field "implementationCounts" value >>= field key

contains :: T.Text -> T.Text -> Assertion
contains needle haystack = assertBool ("Missing text: " ++ T.unpack needle) (needle `T.isInfixOf` haystack)

withTemp :: (FilePath -> IO a) -> IO a
withTemp = bracket create removePathForcibly
  where create = do
          base <- getTemporaryDirectory
          (path, handle) <- openTempFile base "thc-primop-tools-"
          hClose handle
          removeFile path
          createDirectory path
          pure path

coverageTests :: [Test]
coverageTests = map (uncurry (~:))
  [ ("inventory retains unadvertised operations and exact signatures", do
      result <- ok (report (take 2 rows) (Map.singleton "+#" (toJSON (2 :: Int))))
      field "schema" result @?= Right (2 :: Int)
      field "counts" result @?= Right (object ["total" .= (2 :: Int), "advertised" .= (1 :: Int), "unadvertised" .= (1 :: Int)])
      indexed <- byName result
      field "signature" (indexed Map.! "packInt64X2#") @?= Right ("(# Int64#, Int64# #) -> Int64X2#" :: T.Text)
      field "advertised" (indexed Map.! "packInt64X2#") @?= Right False)
  , ("unknown names are not counted as coverage",
      rejects "absent from pinned GHC" (report rows (Map.singleton "misspelled#" (toJSON (1 :: Int)))))
  , ("tuple payload width is not primitive value arity", do
      rejects "arity mismatch" (report rows (Map.singleton "packInt64X2#" (toJSON (2 :: Int))))
      result <- ok (report rows (Map.singleton "packInt64X2#" (toJSON (1 :: Int))))
      (field "counts" result >>= field "advertised") @?= Right (1 :: Int))
  , ("boolean arity and duplicate GHC rows are rejected", do
      rejects "arity mismatch" (report rows (Map.singleton "packInt64X2#" (Bool True)))
      rejects "Duplicate GHC primop" (report (rows ++ take 1 rows) Map.empty))
  , ("family gates count as implemented not missing", do
      result <- ok (derive capability scalars)
      count "implemented" result @?= Right 6
      count "missing" result @?= Right 0
      indexed <- byName result
      forM_ ["tagToEnum#", "dataToTagSmall#", "dataToTagLarge#"] $ \name -> do
        field "status" (indexed Map.! name) @?= Right ("implemented" :: T.Text)
        field "valueArity" (indexed Map.! name) @?= Right (1 :: Int))
  , ("vector pointer and numeric forms use the same implementation metric", do
      indexed <- ok (derive capability scalars) >>= byName
      forM_ ["packInt64X2#", "plusAddr#", "+#"] $ \name ->
        field "status" (indexed Map.! name) @?= Right ("implemented" :: T.Text)
      field "contract" (indexed Map.! "plusAddr#") @?= Right ("Pointer scalar signature" :: T.Text)
      field "contract" (indexed Map.! "+#") @?= Right ("Numeric scalar signature" :: T.Text))
  , ("managed MVars count as implementations", do
      let cap = object ["primitives" .= object ["newMVar#" .= (1 :: Int)], "managedMVarPrimitives" .= object ["newMVar#" .= object []]]
      result <- ok (report [("newMVar#", 1, "State# s -> (# State# s, MVar# s a #)")] (Map.singleton "newMVar#" (toJSON (1 :: Int))) >>=
        \inventory -> classify inventory cap (scalarValue Map.empty))
      indexed <- byName result
      field "status" (indexed Map.! "newMVar#") @?= Right ("implemented" :: T.Text)
      field "contract" (indexed Map.! "newMVar#") @?= Right ("MVar operation" :: T.Text))
  , ("explicit weaks do not claim automatic GC or ephemerons", do
      let limitation = "Automatic weak finalization and ephemerons are not implemented." :: T.Text
          cap = object ["primitives" .= object ["mkWeak#" .= (4 :: Int)], "managedWeakPrimitives" .= object ["mkWeak#" .= object []],
                        "limitations" .= [limitation]]
      result <- ok (report [("mkWeak#", 4, "a -> b -> IO c -> State# RealWorld -> (# State#, Weak# b #)")]
        (Map.singleton "mkWeak#" (toJSON (4 :: Int))) >>= \inventory -> classify inventory cap (scalarValue Map.empty))
      indexed <- byName result
      field "contract" (indexed Map.! "mkWeak#") @?= Right ("Weak-pointer operation" :: T.Text)
      count "implemented" result @?= Right 1
      field "limitations" result @?= Right [limitation]
      ok (checklist result cap) >>= contains "[primop behavior reference](primop-behavior.md)")
  , ("registered implementations change coverage without a scalar gate", do
      let cap = delete "tagToEnum" capability
          changed = modifyPrimitives (\p -> setFields p [("tagToEnum#", toJSON (1 :: Int))]) cap
      before <- ok (derive cap scalars)
      after <- ok (derive changed scalars)
      count "missing" before @?= Right 1
      count "implemented" after @?= Right 6
      forM_ [before, after] $ \value -> case value of
        Object fields -> assertBool "Old partial support metric retained" (not (KM.member "supportCounts" fields))
        _ -> assertFailure "Expected object")
  , ("scalar presence is not an implementation gate", do
      before <- ok (derive capability scalars)
      after <- ok (derive capability (scalarValue Map.empty))
      (field "implementationCounts" before :: Either String Value) @?= field "implementationCounts" after)
  , ("tuple result does not demote arithmetic", do
      let cap = object ["primitives" .= object ["quotRemWord2#" .= (3 :: Int)], "tuplePrimitives" .= object ["quotRemWord2#" .= object []]]
      result <- ok (report [("quotRemWord2#", 3, "Word# -> Word# -> Word# -> (# Word#, Word# #)")]
        (Map.singleton "quotRemWord2#" (toJSON (3 :: Int))) >>= \inventory -> classify inventory cap (scalarValue Map.empty))
      count "implemented" result @?= Right 1
      indexed <- byName result
      field "contract" (indexed Map.! "quotRemWord2#") @?= Right ("Scalar tuple result" :: T.Text))
  , ("empty inventory percentage is defined", do
      let cap = object ["primitives" .= object []]
      result <- ok (report [] Map.empty >>= \r -> classify r cap (scalarValue Map.empty))
      ok (checklist result cap) >>= contains "0 / 0 (0.0%)")
  , ("checklist links behavior reference", do
      result <- ok (derive capability scalars)
      ok (checklist result capability) >>= contains "[primop behavior reference](primop-behavior.md)")
  , ("stale scalar table or wrong target cannot mark support", do
      let unknown = scalarValue (Map.singleton "madeUp#" ([], "IntRep"))
          arity = scalarValue (Map.singleton "+#" (["IntRep"], "IntRep"))
          target = setFields scalars [("targetWordSize", toJSON (32 :: Int))]
      forM_ [unknown, arity, target] $ \bad -> rejects "" (derive capability bad))
  , ("family gates cannot hide false arities or unknown contracts", do
      forM_ [Bool True, toJSON (2 :: Int), String "1"] $ \bad ->
        rejects "Contradictory" (derive (modifyPrimitives (\p -> setFields p [("tagToEnum#", bad)]) capability) scalars)
      rejects "Unrecognized" (derive (setFields capability [("tagToEnum", String "unrestricted")]) scalars)
      rejects "absent from pinned GHC" (declaredPrimitives capability >>= report (init rows)))
  , ("capability changes make docs stale without overwriting", do
      original <- ok (derive capability scalars >>= \r -> checklist r capability)
      let changed = modifyPrimitives (delete "packInt64X2#") capability
      expected <- ok (derive changed scalars >>= \r -> checklist r changed)
      withTemp $ \dir -> do
        let path = dir </> "primops.md"
        T.writeFile path original
        checkDocument path original
        rejected <- try (checkDocument path expected) :: IO (Either ExitCode ())
        assertBool "Stale check unexpectedly passed" (case rejected of Left (ExitFailure _) -> True; _ -> False)
        T.readFile path >>= (@?= original)
      contains "- [x] `+#`" original
      contains "- [x] `packInt64X2#`" original
      contains "- [ ] `packInt64X2#`" expected
      contains "An implemented primitive does not establish whole-program compatibility." original)
  , ("input order and immutable contracts", do
      expected <- ok (derive capability scalars >>= \r -> checklist r capability)
      actual <- ok (declaredPrimitives capability >>= report (reverse rows) >>= \r -> classify r capability scalars >>= \c -> checklist c capability)
      actual @?= expected
      -- Pure inputs are immutable, so there is no mutation escape to copy/check.
      ok (derive capability scalars >>= \r -> checklist r capability) >>= (@?= expected))
  ]

scalarTests :: [Test]
scalarTests = map (uncurry (~:))
  [ ("scalar derivation retains exact operands and ignores unadvertised rows", do
      signatures <- ok (deriveScalars [("+#", ["IntRep", "IntRep", "IntRep"]), ("unknown#", ["StateRep"])]
        (Map.singleton "+#" (toJSON (2 :: Int))))
      signatures @?= Map.singleton "+#" (["IntRep", "IntRep"], "IntRep")
      decoded <- either assertFailure pure (eitherDecodeStrict' (T.encodeUtf8 (renderScalars signatures)) :: Either String Value)
      decoded @?= scalarValue signatures)
  , ("duplicate scalar rows reject", rejects "Invalid scalar signature" (deriveScalars
      [("+#", ["IntRep", "IntRep", "IntRep"]), ("+#", ["IntRep", "IntRep", "IntRep"])] (Map.singleton "+#" (toJSON (2 :: Int)))))
  , ("scalar wrong arity boolean and missing result reject", forM_ [([], toJSON (0 :: Int)), (["IntRep"], toJSON (2 :: Int)), (["IntRep"], Bool True)] $ \(reps, arity) ->
      rejects "Invalid scalar signature" (deriveScalars [("op#", reps)] (Map.singleton "op#" arity)))
  , ("scalar non-scalar representations reject", forM_ ["StateRep", "VecRep", "LiftedRep", "TupleRep"] $ \rep ->
      rejects "Invalid scalar signature" (deriveScalars [("op#", [rep])] (Map.singleton "op#" (toJSON (0 :: Int)))))
  , ("wrong scalar schema and compiler reject", forM_ [[("schema", toJSON (2 :: Int))], [("ghc", String "9.12.2")], [("targetWordSize", Bool True)]] $ \change ->
      rejects "" (signatureTable (setFields scalars change)))
  , ("null family gates reject", rejects "Unrecognized" (declaredPrimitives (setFields capability [("tagToEnum", Null)])))
  , ("floating and string arities reject", forM_ ["{\"primitives\":{\"+#\":2.0}}", "{\"primitives\":{\"+#\":\"2\"}}"] $ \source -> do
      cap <- either (fail . show) pure (eitherDecodeStrict' source :: Either String Value)
      rejects "arity mismatch" (declaredPrimitives cap >>= report rows))
  , ("compiler and word-size checks are explicit", do
      validateCompiler "9.14.1" "[(\"target word size in bits\",\"64\")]" 64 @?= Right ()
      rejects "GHC 9.14.1" (validateCompiler "9.12.2" "[]" 64)
      rejects "64-bit" (validateCompiler "9.14.1" "[(\"target word size in bits\",\"32\")]" 64)
      rejects "64-bit" (validateCompiler "9.14.1" "[(\"target word size in bits\",\"64\")]" 32)
      rejects "Malformed" (validateCompiler "9.14.1" "bad" 64))
  ]

cliTests :: FilePath -> FilePath -> [Test]
cliTests root executable =
  [ "CLI preserves scalar bytes and provenance" ~: isolated (\dir -> do
      run dir ["scalars"] >>= expectSuccess
      before <- BS.readFile (dir </> scalarPath)
      run dir ["scalars", "--write"] >>= expectSuccess
      BS.readFile (dir </> scalarPath) >>= (@?= before)
      proof <- readJson (dir </> "build/scalar-signatures/provenance.json")
      field "entries" proof @?= Right (362 :: Int)
      inputs <- ok (field "inputs" proof :: Either String (Map.Map FilePath String))
      assertBool "Missing implementation source provenance" (Map.member implementationSource inputs)
      forM_ (Map.toList inputs) $ \(input, digest) -> hashFile (dir </> input) >>= (@?= digest)
      digest <- hashFile executable
      field "executableSha256" proof @?= Right digest)
  , "CLI finite known-key catalogue is deterministic and checked" ~: isolated (\dir -> do
      let path = dir </> "src/main/resources/thc/ghc-9.14.1-known-key-names.json"
      run dir ["known-keys", "--write"] >>= expectSuccess
      before <- BS.readFile path
      run dir ["known-keys", "--write"] >>= expectSuccess
      BS.readFile path >>= (@?= before)
      run dir ["known-keys"] >>= expectSuccess
      value <- readJson path
      field "ghc" value @?= Right ("9.14.1" :: T.Text)
      names <- ok (field "names" value :: Either String [Value])
      nameWords <- traverse (ok . field "nameWord") names :: IO [Word32]
      nameWords @?= Map.keys (Map.fromList [(word, ()) | word <- nameWords])
      forM_ names $ \row -> when (field "namespace" row == Right (4 :: Int)) $ do
        parent <- ok (field "fieldParent" row :: Either String T.Text)
        assertBool "Missing canonical field parent" (not (T.null parent))
      primops <- filterM (fmap (== ("primop" :: T.Text)) . ok . field "category") names
      length primops @?= length allThePrimOpIds
      plus <- case [row | row <- primops, field "occurrence" row == Right ("+#" :: T.Text)] of
        [row] -> pure row
        _ -> assertFailure "Missing or duplicate +# primop identity" >> fail "primop"
      field "module" plus @?= Right ("GHC.Internal.Prim" :: T.Text)
      field "namespace" plus @?= Right (0 :: Int)
      BS.appendFile path " "
      stale <- BS.readFile path
      run dir ["known-keys"] >>= expectFailure "Known-key catalogue differs"
      BS.readFile path >>= (@?= stale))
  , "CLI axiom oracle is compiler-owned, deterministic and fresh" ~: isolated (\dir -> do
      let path = dir </> "axiom-oracle.json"
      run dir ["known-keys", "--write-axiom-oracle", path] >>= expectSuccess
      before <- BS.readFile path
      BS.readFile (root </> "src/test/resources/thc/native-hi-axiom-oracle.json") >>= (@?= before)
      run dir ["known-keys", "--write-axiom-oracle", path] >>= expectSuccess
      BS.readFile path >>= (@?= before)
      value <- readJson path
      field "ghc" value @?= Right ("9.14.1" :: T.Text)
      digest <- hashFile (dir </> implementationSource)
      field "generatorSha256" value @?= Right digest
      cases <- ok (field "cases" value :: Either String [Value])
      catalogue <- ok knownKeyCatalogue
      rules <- ok (field "axiomRules" catalogue :: Either String [Value])
      actual <- Map.fromList <$> traverse (\row -> (,) <$> ok (field "name" row :: Either String T.Text) <*> pure ()) rules
      observed <- Map.fromList <$> traverse (\row -> (,) <$> ok (field "rule" row :: Either String T.Text) <*> pure ()) cases
      observed @?= actual)
  , "CLI stale scalar check leaves contract untouched" ~: isolated (\dir -> do
      BS.appendFile (dir </> scalarPath) " "
      stale <- BS.readFile (dir </> scalarPath)
      result <- run dir ["scalars"]
      expectFailure "Scalar signature contract differs" result
      BS.readFile (dir </> scalarPath) >>= (@?= stale))
  , "CLI stale checklist removes previous report without rewriting docs" ~: isolated (\dir -> do
      run dir ["coverage", "--write-checklist"] >>= expectSuccess
      run dir ["coverage", "--check"] >>= expectSuccess
      result <- readJson (dir </> "build/primop-coverage.json")
      count "implemented" result @?= Right 1491
      BS.appendFile (dir </> checklistPath) "stale\n"
      stale <- BS.readFile (dir </> checklistPath)
      run dir ["coverage", "--check"] >>= expectFailure "is stale"
      doesFileExist (dir </> "build/primop-coverage.json") >>= (@?= False)
      BS.readFile (dir </> checklistPath) >>= (@?= stale))
  , "CLI rejects ambiguous modes" ~: isolated (\dir ->
      run dir ["coverage", "--check", "--write-checklist"] >>= expectFailure "Usage")
  ]
  where
    isolated action = withTemp $ \dir -> do
      forM_ [implementationSource, "thc.cabal", capabilityPath, scalarPath, checklistPath] $ \path -> do
        createDirectoryIfMissing True (takeDirectory (dir </> path))
        copyFile (root </> path) (dir </> path)
      action dir
    run dir args = readCreateProcessWithExitCode ((proc executable args) {cwd = Just dir}) ""
    expectSuccess (code, _, stderr) = assertEqual stderr ExitSuccess code
    expectFailure message (code, _, stderr) = do
      assertBool stderr (code /= ExitSuccess)
      assertBool stderr (message `isInfixOf` stderr)

main :: IO ()
main = do
  root <- getCurrentDirectory
  executable <- findExecutable "thc-primops" >>= maybe (fail "Missing Cabal build-tool thc-primops") pure
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  let apiTest = "direct pinned API preserves structural scalar filter" ~: do
        (inventory, scalarRows, _) <- queryGhc ghc
        length inventory @?= 1491
        case [signature | (name, _, signature) <- inventory, name == "catch#"] of
          [signature] -> contains "forall {q :: RuntimeRep} {k :: Levity}" signature
          _ -> assertFailure "Missing/duplicate catch# signature"
        let names = map fst scalarRows
        assertBool "Tuple/vector/polymorphic scalar leak" (all (`notElem` names) ["packInt64X2#", "tagToEnum#", "newMVar#"])
        assertBool "Pointer scalar omitted" ("plusAddr#" `elem` names)
  let knownKeysTest = "known-key words agree with actual GHC interface serialization" ~: do
        _ <- ok knownKeyCatalogue
        table <- BinSymbolTable <$> newFastMutInt 0 <*> newIORef emptyUFM
        writer <- Binary.openBinMem 4096
        forM_ knownKeyNames (putName table writer)
        Binary.withBinBuffer writer $ \bytes -> do
          reader <- Binary.unsafeUnpackBinBuffer bytes
          forM_ knownKeyNames $ \name -> do
            actual <- Binary.get reader :: IO Word32
            expected <- ok (knownKeyWord name)
            actual @?= expected
  let wiredTypesTest = "wired metadata preserves every compiler declaration and full type" ~: do
        catalogue <- ok knownKeyCatalogue
        forM_ [("primops", allThePrimOpIds), ("wiredIds", wiredInIds ++ ghcPrimIds)] $ \(key, identifiers) -> do
          entries <- ok (field key catalogue :: Either String [Value])
          expected <- traverse (ok . idValue) identifiers
          entries @?= expected
        tycons <- ok (field "tycons" catalogue :: Either String [Value])
        names <- traverse (ok . field "name") tycons :: IO [Value]
        length names @?= Map.size (Map.fromList [(encode name, ()) | name <- names])
        forM_ (Prim.primTyCons ++ Builtin.wiredInTyCons ++
          [GHC.promoteDataCon con | tc <- Builtin.wiredInTyCons, con <- GHC.tyConDataCons tc]) $ \tc -> do
          name <- ok (metadataName (GHC.tyConName tc))
          assertBool "Compiler-owned tycon missing" (name `elem` names)
          case [entry | entry <- tycons, field "name" entry == Right name] of
            [entry] -> do
              field "kind" entry @?= closedTypeValue (GHC.tyConKind tc)
              field "roles" entry @?= Right (map roleValue (GHC.tyConRoles tc))
              constructors <- ok (field "constructors" entry :: Either String [Value])
              expectedConstructors <- traverse (ok . constructorValue) (GHC.tyConDataCons tc)
              constructors @?= expectedConstructors
            _ -> assertFailure "Duplicate compiler-owned tycon"
  let lexicalTypesTest = "Unique-bound types retain distinct lexical binders" ~: do
        let variable index = GHC.mkTyVar (GHC.mkInternalName (mkUnique 'z' index)
              (GHC.mkTyVarOcc "a") GHC.noSrcSpan) GHC.liftedTypeKind
            outer = variable 1
            inner = variable 2
            ty = GHC.mkSpecForAllTys [outer, inner]
              (GHC.mkVisFunTyMany (GHC.mkTyVarTy outer) (GHC.mkTyVarTy inner))
        encoded <- ok (closedTypeValue ty)
        let array (Array values) = toList values
            array _ = []
        case array encoded of
          [String "forall", outerBinder, _, body] -> case (array outerBinder, array body) of
            ([outerName, Bool False, _], [String "forall", innerBinder, _, result]) ->
              case (array innerBinder, array result) of
                ([innerName, Bool False, _], [String "fun", _, _, argument, returned]) -> do
                  assertBool "Serialized inner binder captured outer binder" (outerName /= innerName)
                  argument @?= node "var" [outerName]
                  returned @?= node "var" [innerName]
                _ -> assertFailure "Missing serialized function/binder"
            _ -> assertFailure "Missing serialized nested forall"
          _ -> assertFailure "Missing serialized forall"
  counts <- runTestTT (TestList (coverageTests ++ scalarTests ++
    [apiTest, knownKeysTest, wiredTypesTest, lexicalTypesTest] ++ cliTests root executable))
  unless (errors counts == 0 && failures counts == 0) exitFailure
