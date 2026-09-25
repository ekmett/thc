-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}
module OriginalFdReadyFixtures (prepareOriginalFdReady) where

import Control.Monad (forM, unless)
import Data.Aeson (Value(..), eitherDecode, object, toJSON, (.=))
import qualified Data.Aeson.Key as Key
import qualified Data.Aeson.KeyMap as KeyMap
import qualified Data.ByteString.Char8 as BS
import qualified Data.ByteString.Lazy as BL
import Data.Foldable (toList)
import Data.IORef (newIORef, writeIORef)
import Data.List (isPrefixOf, nubBy, sort, sortOn)
import qualified Data.Map.Strict as Map
import qualified Data.Text as Text
import qualified Data.Text.Encoding as Text
import Data.Traversable (mapAccumL)
import FixtureSupport
import GHC hiding (entry, exprType)
import GHC.Plugins hiding (line)
import GHC.Core.TyCo.Compare (eqType)
import GHC.Driver.Env.KnotVars (KnotVars(..), lookupKnotVars)
import GHC.Iface.Binary
import GHC.IfaceToCore (typecheckIface)
import GHC.Tc.Utils.Monad (initIfaceCheck)
import GHC.Types.TypeEnv (emptyTypeEnv, typeEnvIds, typeEnvTyCons)
import GHC.Unit.Module.ModDetails (md_types)
import GHC.Unit.Module.WholeCoreBindings (emptyIfaceForeign)
import qualified GHC.Types.ForeignCall as Foreign
import System.Directory (createDirectoryIfMissing, listDirectory)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>), takeExtension)
import THC.Interface
import THC.Plugin (serializePostTidyCore)
import Text.Read (readMaybe)

directory, source, driver :: FilePath
directory = "build/original-fd-ready"
source = "compiler/test-fixtures/OriginalFdReadyAudit.hs"
driver = "compiler/test-fixtures/OriginalFdReadyAuditNative.hs"

entries :: [String]
entries = ["originalReadySafe", "originalReadyUnsafe"]

type Row = (String, String, Int, Int, Int, Int, Int, Int)

readyCall :: Id -> Maybe Foreign.Safety
readyCall v = case isFCallId_maybe v of
  Just (Foreign.CCall (Foreign.CCallSpec (Foreign.StaticTarget _ symbol _ True) Foreign.CCallConv safety))
    | unpackFS symbol == "fdReady", safety `elem` [Foreign.PlaySafe, Foreign.PlayRisky] -> Just safety
  _ -> Nothing

variables :: CoreExpr -> [Id]
variables expression = case expression of
  Var v | isId v -> [v]
  App f x -> variables f ++ variables x
  Lam _ body -> variables body
  Let bindings body -> concatMap (variables . snd) (flattenBinds [bindings]) ++ variables body
  Case scrutinee _ _ alternatives -> variables scrutinee ++ concat [variables body | Alt _ _ body <- alternatives]
  Cast body _ -> variables body
  Tick _ body -> variables body
  _ -> []

-- Only the fixture's foreign heads change. The replacement Id (including exact
-- StaticTarget unit, convention, safety and type) comes from installed GHC Core.
replaceCalls :: [Id] -> CoreExpr -> CoreExpr
replaceCalls originals expression = case expression of
  Var v | Just safety <- readyCall v -> case [original | original <- originals, readyCall original == Just safety] of
    [original] | eqType (idType v) (idType original) -> Var original
    _ -> error "Original fdReady FCallId/type is missing, ambiguous or incompatible"
  App f x -> App (replaceCalls originals f) (replaceCalls originals x)
  Lam v body -> Lam v (replaceCalls originals body)
  Let bindings body -> Let (mapBind (replaceCalls originals) bindings) (replaceCalls originals body)
  Case scrutinee v result alternatives -> Case (replaceCalls originals scrutinee) v result
    [Alt con binders (replaceCalls originals body) | Alt con binders body <- alternatives]
  Cast body coercion -> Cast (replaceCalls originals body) coercion
  Tick tick body -> Tick tick (replaceCalls originals body)
  _ -> expression

mapBind :: (CoreExpr -> CoreExpr) -> CoreBind -> CoreBind
mapBind f (NonRec v rhs) = NonRec v (f rhs)
mapBind f (Rec pairs) = Rec [(v, f rhs) | (v, rhs) <- pairs]

jsonField :: Key.Key -> Value -> Value
jsonField key (Object fields) = maybe (error "Missing readiness fixture field") id (KeyMap.lookup key fields)
jsonField _ _ = error "Expected readiness fixture object"

changeField :: Key.Key -> (Value -> Value) -> Value -> Value
changeField key change value@(Object fields) = Object (KeyMap.insert key (change (jsonField key value)) fields)
changeField _ _ _ = error "Expected readiness fixture object for mutation"

changeIndex :: Int -> (Value -> Value) -> Value -> Value
changeIndex wanted change (Array values) = Array (snd (mapAccumL step 0 values))
  where step index value = (index + 1, if index == wanted then change value else value)
changeIndex _ _ _ = error "Expected readiness fixture array for mutation"

rewriteReady :: (Value -> Value) -> Value -> Value
rewriteReady change value = case value of
  Object fields -> Object $ KeyMap.mapWithKey (\key child ->
    if key == "foreignCall" && jsonField "symbol" (jsonField "target" child) == String "fdReady"
    then change child else rewriteReady change child) fields
  Array values -> Array (fmap (rewriteReady change) values)
  _ -> value

negativeCases :: [(String, Value -> Value)]
negativeCases =
  [("wrong-unit", changeField "target" (changeField "unit" (const (String "main"))))
  ,("dynamic-target", changeField "target" (changeField "kind" (const (String "dynamic"))))
  ,("non-function", changeField "target" (changeField "isFunction" (const (Bool False))))
  ,("wrong-convention", changeField "convention" (const (String "capi")))
  ,("interruptible", changeField "safety" (const (String "interruptible")))
  ,("wrong-arity", changeField "arity" (const (Number 4)))
  ,("wrong-supplied-arity", changeField "suppliedArity" (const (Number 6)))
  ,("boolean-schema", changeField "schema" (const (Bool True)))
  ,("signed-cbool", changeField "argumentReps" (changeIndex 1 (wrongRep "Int8Rep")))
  ,("machine-timeout", changeField "argumentReps" (changeIndex 2 (wrongRep "IntRep")))
  ,("scalar-state", changeField "argumentReps" (changeIndex 4 (wrongRep "IntRep")))
  ,("machine-result", changeField "resultRep" (wrongRep "IntRep"))]
  where wrongRep primitive = changeField "primReps" (const (toJSON [primitive :: String]))

foreignApplications :: Value -> [Value]
foreignApplications value = case value of
  Object fields -> concatMap foreignApplications fields
  Array values ->
    let children = toList values
        selected = case children of
          [String "app", _, _, _, _, _, Object metadata]
            | Just descriptor <- KeyMap.lookup "foreignCall" metadata
            , jsonField "symbol" (jsonField "target" descriptor) == String "fdReady" -> [value]
          _ -> []
    in selected ++ concatMap foreignApplications children
  _ -> []

prepareOriginalFdReady :: FilePath -> IO ()
prepareOriginalFdReady root = do
  let execute = runLogged 180 root (directory </> "logs")
      binary = directory </> "native/oracle"
      templateInterface = root </> directory </> "native/OriginalFdReadyAudit.hi"
  createDirectoryIfMissing True (root </> directory </> "native")
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  ghcPkg <- maybe "ghc-pkg" id <$> lookupEnv "GHC_PKG"
  version <- execute "ghc-version" [] ghc ["--numeric-version"]
  unless (commandStdout version == "9.14.1\n") (die "Original fdReady requires GHC9.14.1")
  info <- execute "ghc-info" [] ghc ["--info"]
  case readMaybe (BS.unpack (commandStdout info)) :: Maybe [(String, String)] of
    Just fields | Just host <- lookup "Host platform" fields,
                  lookup "Target platform" fields == Just host,
                  lookup "target word size" fields == Just "8",
                  any (`isPrefixOf` host) ["x86_64-", "aarch64-"] -> pure ()
    _ -> die "Original fdReady requires a native 64-bit selected compiler"
  libdirResult <- execute "ghc-libdir" [] ghc ["--print-libdir"]
  importsResult <- execute "ghc-internal-imports" [] ghcPkg ["field", "ghc-internal", "import-dirs", "--simple-output"]
  let oneLine result = case lines (BS.unpack (commandStdout result)) of
        [line] -> line
        _ -> error "Expected one selected compiler path"
      libdir = oneLine libdirResult
      installed = oneLine importsResult </> "GHC/Internal/IO/FD.hi"
  compiled <- execute "native-build" [] ghc ["--make", "-O2", "-fforce-recomp", "-dcore-lint", "-dstg-lint",
    "-fwrite-if-simplified-core", "-package", "ghc-internal", "-package", "unix",
    "-i" ++ root </> "compiler/test-fixtures", "-odir", root </> directory </> "native",
    "-hidir", root </> directory </> "native", driver, "-o", root </> binary]
  observed <- execute "native-observations" [] (root </> binary) [root </> directory </> "native/private-file"]
  rows <- maybe (die "Malformed native fdReady oracle") pure
    (readMaybe (BS.unpack (commandStdout observed)) :: Maybe [Row])
  unless (length rows == 168) (die "Unexpected native fdReady row count")
  let oracle = directory </> "oracle.json"
  writeJson (root </> oracle) $ toJSON
    [object ["entry" .= entry, "scenario" .= scenario, "writing" .= writing, "milliseconds" .= milliseconds,
      "socket" .= socket, "result" .= result, "errnoBefore" .= before, "errnoAfter" .= after]
    | (entry, scenario, writing, milliseconds, socket, result, before, after) <- rows]
  let originalPath = directory </> "OriginalFDDeclarations.json"
      templatePath = directory </> "Template.json"
      adaptedPath = directory </> "OriginalFdReadyAudit.json"
      factsPath = directory </> "facts.json"
  runGhc (Just libdir) $ do
    initialFlags <- getSessionDynFlags
    _ <- setSessionDynFlags (gopt_unset initialFlags Opt_IgnoreInterfacePragmas)
    flags <- getSessionDynFlags
    environment <- getSession
    liftIO $ do
      raw <- readBinIface (targetProfile flags) (hsc_NC environment) CheckHiWay QuietBinIFace installed
      unless (moduleNameString (moduleName (mi_module raw)) == "GHC.Internal.IO.FD" &&
              unitString (moduleUnit (mi_module raw)) == "ghc-internal") (die "Wrong original FD owner")
      -- This fixture needs original FCallIds, not a runnable FD module. Ordinary
      -- interface declarations retain those calls even in stock thin interfaces.
      -- Keep this proof-only hydration separate from production whole-Core loading.
      types <- newIORef emptyTypeEnv
      let owner = mi_module raw
          oldKnots = hsc_type_env_vars environment
          domain = case oldKnots of NoKnotVars -> []; KnotVars ms _ -> ms
          knots = KnotVars (owner : filter (/= owner) domain) $ \other ->
            if other == owner then Just types else lookupKnotVars oldKnots other
          tied = (hscSetFlags flags environment) { hsc_type_env_vars = knots }
      details <- initIfaceCheck (text "THC fixture original foreign declarations") tied (typecheckIface raw)
      writeIORef types (md_types details)
      let declarations = [(v, body) | v <- sortOn getOccString (typeEnvIds (md_types details)),
            nameModule_maybe (varName v) == Just owner,
            Just body <- [maybeUnfoldingTemplate (realIdUnfolding v)],
            any ((/= Nothing) . readyCall) (variables body)]
      templateRaw <- readBinIface (targetProfile flags) (hsc_NC environment) CheckHiWay QuietBinIFace templateInterface
      templateLoaded <- loadInterfaceCore environment (mi_module templateRaw) templateInterface
      template <- maybe (die "Missing compiled readiness template Core") pure templateLoaded
      let calls = [v | (_, body) <- declarations, v <- variables body,
                       Just _ <- [readyCall v]]
          originals = nubBy (\a b -> readyCall a == readyCall b) calls
          replacements = [v | (_, body) <- flattenBinds (interfaceBindings template), v <- variables body,
                              Just _ <- [readyCall v]]
      unless (length calls == 5 && length originals == 2 && length replacements == 2)
        (die "Unexpected original/template fdReady call inventory")
      unless (all (not . isExternalName . varName) originals)
        (die "Original FCallIds must retain their private GHC identities")
      unless (all (\v -> case isFCallId_maybe v of
        Just (Foreign.CCall (Foreign.CCallSpec (Foreign.StaticTarget _ _ (Just foreignOwner) True) _ _)) -> unitString foreignOwner == "ghc-internal"
        _ -> False) originals) (die "Missing original ghc-internal foreign owner")
      -- Use the real serializer for call metadata, then retain only call nodes
      -- in a non-executable diagnostic document. The temporary projection is
      -- not a module export and makes no claim about original foreign products.
      originalText <- serializePostTidyCore flags ["unit-qualified"] owner
        (typeEnvTyCons (md_types details)) [NonRec v body | (v, body) <- declarations] emptyIfaceForeign
      originalProjection <- either die pure (eitherDecode (BL.fromStrict (Text.encodeUtf8 (Text.pack originalText))))
      let originalApplications = foreignApplications originalProjection
      unless (length originalApplications == 5) (die "Original declaration serialization lost fdReady calls")
      templateText <- interfaceCoreJSON ["unit-qualified"] template
      let adapted = map (mapBind (replaceCalls originals)) (interfaceBindings template)
          adaptedCalls = [v | (_, body) <- flattenBinds adapted, v <- variables body,
                              Just _ <- [readyCall v]]
      unless (length adaptedCalls == 2 && all (`elem` originals) adaptedCalls)
        (die "Adapted consumers did not retain the exact original GHC Ids")
      adaptedText <- serializePostTidyCore flags ["unit-qualified"] (interfaceModule template)
        (typeEnvTyCons (md_types (interfaceDetails template))) adapted (interfaceForeign template)
      writeJson (root </> originalPath) $ object
        ["schema" .= (1 :: Int), "originalInterface" .= installed,
         "projection" .= ("original-interface-foreign-declarations-only" :: String),
         "completeModule" .= False, "calls" .= originalApplications]
      writeFile (root </> templatePath) templateText
      writeFile (root </> adaptedPath) adaptedText
      writeJson (root </> factsPath) $ object
        ["originalInterface" .= installed, "originalCalls" .= length calls, "adaptedCalls" .= length replacements,
         "installedArtifactsHashed" .= False, "typeEqualityChecked" .= True,
         "originalIdentityChecked" .= True, "originalNamesExternal" .= map (isExternalName . varName) originals,
         "originalProjection" .= ("original-interface-foreign-declarations-only" :: String),
         "boundary" .= ("GHC-compiled test consumer with original installed FD FCallIds; not unchanged Handle/FD execution" :: String)]
  audits <- forM entries $ \entry -> do
    let output = directory </> entry ++ ".audit.json"
    audited <- execute ("audit-" ++ entry) [] "python3"
      ["scripts/audit-core.py", adaptedPath, "--entry", entry, "--output", output]
    pure (entry, output, audited)
  adaptedJSON <- either (die . ("Malformed generated readiness Core: " ++)) pure . eitherDecode =<<
    BL.readFile (root </> adaptedPath)
  createDirectoryIfMissing True (root </> directory </> "negative")
  negatives <- fmap concat $ forM negativeCases $ \(label, change) -> do
    let input = directory </> "negative" </> label ++ ".json"
        mutated = rewriteReady change adaptedJSON
    unless (mutated /= adaptedJSON) (die "Readiness negative control did not mutate the descriptor")
    writeJson (root </> input) mutated
    forM entries $ \entry -> do
      let output = directory </> "negative" </> label ++ "-" ++ entry ++ ".audit.json"
      audited <- runLoggedExpect 1 180 root (directory </> "logs") ("negative-" ++ label ++ "-" ++ entry) [] "python3"
        ["scripts/audit-core.py", input, "--entry", entry, "--output", output]
      report <- either die pure . eitherDecode =<< BL.readFile (root </> output)
      unless (jsonField "accepted" report == Bool False &&
        case jsonField "issues" report of
          Array issues -> any ((== String "foreign-call") . jsonField "code") issues
          _ -> False) (die "Readiness negative control did not reject the original foreign descriptor")
      pure (input, output, audited)
  compilerFiles <- listDirectory (root </> "compiler/THC")
  scriptFiles <- listDirectory (root </> "scripts")
  let commands = [version, info, libdirResult, importsResult, compiled, observed] ++
        [command | (_,_,command) <- audits] ++ [command | (_,_,command) <- negatives]
      inputs = sort $ [source, driver, "test/haskell-fixtures/OriginalFdReadyFixtures.hs",
        "test/haskell-fixtures/Main.hs", "test/haskell-fixtures/FixtureSupport.hs", "thc.cabal",
        "scripts/audit-core.py", "scripts/core-capabilities.json"] ++
        ["compiler/THC" </> name | name <- compilerFiles, takeExtension name == ".hs"] ++
        ["scripts" </> name | name <- scriptFiles, "core_" `isPrefixOf` name, takeExtension name == ".py"]
      artifacts = [oracle, originalPath, templatePath, adaptedPath, factsPath, binary,
        directory </> "native/private-file"] ++ [path | (_,path,_) <- audits] ++
        concat [[input, output] | (input,output,_) <- negatives] ++ concatMap commandArtifacts commands
  inputHashes <- hashes root inputs
  artifactHashes <- hashes root artifacts
  writeJson (root </> directory </> "manifest.json") $ object
    ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String), "entries" .= entries,
     "nativeRows" .= length rows, "negativeAudits" .= length negatives,
     "inputHashes" .= inputHashes, "artifactHashes" .= artifactHashes,
     "audits" .= Map.fromList [(entry,path) | (entry,path,_) <- audits], "commands" .= map commandRecord commands]
  putStrLn "original-fd-ready:168 native observations, actual original FCallIds,2 strict entries,24 rejected ABI controls"
