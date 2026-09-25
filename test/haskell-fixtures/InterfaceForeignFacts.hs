-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}
module InterfaceForeignFacts (prepareForeignAssociation, inspectInstalledBound) where

import Control.Monad (forM, unless)
import Data.Aeson (Value(..), object, (.=))
import Data.List (isInfixOf, sortOn)
import qualified Data.Map.Strict as Map
import Data.Maybe (isJust, mapMaybe)
import qualified Data.Text as Text
import GHC hiding (exprType)
import GHC.Plugins
import GHC.Cmm.CLabel (mkClosureLabel, pprCLabel)
import GHC.Iface.Binary (readBinIface, CheckHiWay(..), TraceBinIFace(..))
import GHC.Iface.Ext.Fields (getExtensibleFields)
import GHC.Tc.Utils.TcType (tcSplitPiTys, tcSplitIOType_maybe)
import GHC.Types.RepType (typePrimRep)
import qualified GHC.Unit.Module.WholeCoreBindings as ForeignCore
import System.Directory (copyFile, createDirectoryIfMissing, renameFile)
import System.Exit (die)
import System.FilePath ((</>), takeDirectory)
import FixtureSupport (CommandResult, runLogged, writeJson)
import THC.Interface

check :: Bool -> String -> IO ()
check condition message = unless condition (die message)

-- Facts about ALL external Core binders, not a foreign-export classifier.
-- Neither a $fstable occurrence nor an external Core Name proves a C export.
bindingFacts :: DynFlags -> CoreProgram -> Value
bindingFacts flags bindings = toValue
  [v | (v, _) <- flattenBinds bindings, isExternalName (varName v)]
  where
    render = showSDoc flags
    rep ty = object ["type" .= render (ppr ty),
                     "primReps" .= map (render . ppr) (typePrimRep ty)]
    fact v = let (arguments, result) = tcSplitPiTys (idType v) in object
      ["name" .= getOccString v, "type" .= render (ppr (idType v)),
       "details" .= render (ppr (idDetails v)),
       "closureLabel" .= closureLabel flags v,
       "arguments" .= map rep (mapMaybe anonPiTyBinderType_maybe arguments),
       "result" .= rep result, "resultIsIO" .= isJust (tcSplitIOType_maybe result)]
    toValue vs = object ["binders" .= map fact (sortOn getOccString vs)]

closureLabel :: DynFlags -> Id -> String
closureLabel flags v = showSDoc flags
  (pprCode (pprCLabel (targetPlatform flags) (mkClosureLabel (varName v) (idCafInfo v))))

declaredIn :: DynFlags -> String -> Id -> Bool
declaredIn flags source v = ("extern StgClosure " ++ closureLabel flags v ++ ";") `isInfixOf` source

stubText :: InterfaceCore -> IO (String, String)
stubText core = case interfaceForeign core of
  ForeignCore.IfaceForeign (Just (ForeignCore.IfaceCStubs header source initializers finalizers)) files -> do
    check (length initializers == 1 && null finalizers && null files)
      "Association control changed its registration obligations"
    pure (header, source)
  _ -> die "Association control lost its real foreign stub"

-- A counterexample to reconstructing a C symbol from typed interface binders:
-- the typed declarations, exports, Core binder facts and GHC closure labels
-- agree while the two real C exports differ. No raw-C parser is implemented.
prepareForeignAssociation :: FilePath -> FilePath -> FilePath -> FilePath -> String -> IO [CommandResult]
prepareForeignAssociation root directory ghc libdir unitName = do
  let source = directory </> "source/InterfaceForeignAlias.hs"
      variants = [("a", "thc_interface_alias_a"), ("b", "thc_interface_alias_b")]
  copyFile (root </> "compiler/test-fixtures/InterfaceForeignAlias.hs") (root </> source)
  commands <- forM variants $ \(variant, symbol) -> do
    let output = directory </> "foreign-alias" </> variant
    createDirectoryIfMissing True (root </> output)
    runLogged 180 root (directory </> "logs") ("foreign-alias-" ++ variant) [] ghc
      ["-c", "-O2", "-fforce-recomp", "-dcore-lint", "-this-unit-id", unitName,
       "-fwrite-if-simplified-core", "-DTHC_FOREIGN_C_LABEL=\"" ++ symbol ++ "\"",
       "-odir", output, "-hidir", output, "-stubdir", output, source]
  renameFile (root </> source) (root </> source ++ ".saved")
  results <- forM variants $ \(variant, symbol) -> runGhc (Just libdir) $ do
    initial <- getSessionDynFlags
    initialEnv <- getSession
    (flags, leftovers, _) <- parseDynamicFlags (hsc_logger initialEnv) initial
      (map noLoc ["-package-db", root </> directory </> "full/package.conf.d", "-package-id", unitName])
    liftIO $ check (null leftovers) "Unexpected association control flags"
    _ <- setSessionDynFlags flags
    environment <- getSession
    liftIO $ do
      let path = root </> directory </> "foreign-alias" </> variant </> "InterfaceForeignAlias.hi"
          expected = mkModule (stringToUnit unitName) (mkModuleName "InterfaceForeignAlias")
      raw <- readBinIface (targetProfile flags) (hsc_NC environment) CheckHiWay QuietBinIFace path
      loaded <- loadInterfaceCore environment expected path
      core <- maybe (die "Association control lost complete Core") pure loaded
      (header, cSource) <- stubText core
      check (symbol `isInfixOf` header && symbol `isInfixOf` cSource)
        "Requested C export name missing from real stub"
      let binders = [v | (v, _) <- flattenBinds (interfaceBindings core), isExternalName (varName v)]
          -- Test observation only: GHC's own closure-label rendering appears
          -- in its emitted C. This is NOT an accepted executable association.
          referenced = filter (declaredIn flags cSource) binders
          facts = bindingFacts flags (interfaceBindings core)
          render = showSDoc flags
          declarations = map (render . ppr . snd) (mi_decls raw)
          exports = render (ppr (mi_exports raw))
      check (length referenced == 1) "Expected exactly one observed C closure reference"
      check (all (not . isFCallId) referenced) "Export binder unexpectedly has foreign-call IdDetails"
      check (Map.null (getExtensibleFields (mi_ext_fields raw))) "Unexpected typed interface extension"
      rendered <- interfaceCoreJSON ["unit-qualified"] core
      writeFile (root </> directory </> "foreign-alias" </> variant ++ ".json") rendered
      pure (facts, declarations, exports, header, cSource, object
        ["variant" .= variant, "symbol" .= symbol, "facts" .= facts,
         "observedCClosure" .= bindingFacts flags (map (\v -> NonRec v (Var v)) referenced),
         "interfaceExtensionFields" .= ([] :: [String]), "executableAssociation" .= False])
  case results of
    [(factsA, declsA, exportsA, headerA, cA, a), (factsB, declsB, exportsB, headerB, cB, b)] -> do
      check (factsA == factsB && declsA == declsB && exportsA == exportsB)
        "C-name-only change unexpectedly changed typed interface facts"
      check (headerA /= headerB && cA /= cB) "C-name control did not distinguish foreign exports"
      let rename = Text.replace "thc_interface_alias_a" "thc_interface_alias_b" . Text.pack
      check (rename headerA == Text.pack headerB && rename cA == Text.pack cB)
        "Foreign artifacts changed by more than the external C symbol"
      writeJson (root </> directory </> "foreign-association.json") $ object
        ["schema" .= (1 :: Int), "typedFactsEqual" .= True, "typedDeclarationsEqual" .= True,
         "haskellExportsEqual" .= True, "foreignArtifactsDiffer" .= True,
         "sourceDeleted" .= True, "variants" .= [a, b]]
    _ -> die "Expected exactly two foreign association controls"
  pure commands

-- The selected installed compiler may legitimately have thin boot interfaces.
-- Inspect Bound only when its real complete Core exists; never copy/hash it.
inspectInstalledBound :: HscEnv -> FilePath -> FilePath -> IO ()
inspectInstalledBound environment charPath output = do
  let flags = hsc_dflags environment
      path = takeDirectory charPath </> "Conc/Bound.hi"
  raw <- readBinIface (targetProfile flags) (hsc_NC environment) CheckHiWay QuietBinIFace path
  loaded <- loadInterfaceCore environment (mi_module raw) path
  value <- case loaded of
    Nothing -> pure (object ["completeCore" .= False])
    Just core -> do
      (_, cSource) <- stubText core
      let binders = [v | (v, _) <- flattenBinds (interfaceBindings core), isExternalName (varName v)]
          referenced = filter (declaredIn flags cSource) binders
      check (length referenced == 1 && "forkOS_entry" `isInfixOf` cSource &&
             "ghc_hs_iface->runIO_closure" `isInfixOf` cSource)
        ("Installed Bound foreign stub no longer matches the inspected control: " ++ show (map getOccString referenced))
      check (all (not . isFCallId) referenced) "Installed export acquired an FCallId"
      pure (object ["completeCore" .= True, "module" .= moduleNameString (moduleName (mi_module raw)),
        "unit" .= unitString (moduleUnit (mi_module raw)),
        "observedCClosure" .= bindingFacts flags (map (\v -> NonRec v (Var v)) referenced),
        "interfaceExtensionFields" .= Map.keys (getExtensibleFields (mi_ext_fields raw)),
        "executableAssociation" .= False])
  writeJson output value
