-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : ByteStringDecimalFixtures
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC 9.14.1 compiler API
--
-- Fixture acquisition support for byte string decimal.
module ByteStringDecimalFixtures (prepareByteStringDecimal) where

import Control.Monad (forM, unless)
import Data.Aeson (object, toJSON, (.=))
import qualified Data.ByteString as Bytes
import qualified Data.ByteString.Char8 as BS
import Data.Char (isAlphaNum, toUpper)
import Data.Int (Int64)
import Data.IORef (newIORef, writeIORef)
import Data.List (nubBy, stripPrefix)
import Data.Word (Word8)
import FixtureSupport
import Foreign.Marshal.Alloc (allocaBytes)
import Foreign.Marshal.Utils (fillBytes)
import Foreign.Ptr (Ptr, plusPtr, minusPtr)
import GHC hiding (entry, exprType)
import GHC.Plugins
import GHC.Core.TyCo.Compare (eqType)
import GHC.Core.SimpleOpt (simpleOptExpr)
import GHC.Core.Opt.Arity (exprArity)
import GHC.Driver.Config (initSimpleOpts)
import GHC.Driver.Env.KnotVars (KnotVars(..), lookupKnotVars)
import GHC.Driver.Main (hscSimplify, hscTidy, hscCompileCoreExpr)
import GHC.Iface.Binary
import GHC.IfaceToCore (typecheckIface)
import GHC.Runtime.Interpreter (wormhole)
import GHC.Tc.Utils.Monad (initIfaceCheck)
import GHC.Types.TypeEnv (emptyTypeEnv, typeEnvIds)
import GHC.Types.Avail (availName)
import GHC.Unit.Module.ModDetails (md_types)
import GHC.Unit.Module.WholeCoreBindings (emptyIfaceForeign)
import qualified GHC.Types.ForeignCall as F
import System.Directory (createDirectoryIfMissing, doesFileExist, removeFile)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>))
import THC.Plugin (serializeOptimizedCore, serializePostTidyCore)
import Unsafe.Coerce (unsafeCoerce)

operations :: [(String, String)]
operations = [("decimal","_hs_bytestring_long_long_int_dec"),("padded18","_hs_bytestring_long_long_int_dec_padded18")]

byteStringUnit :: String -> Bool
byteStringUnit value = case stripPrefix "bytestring-0.12.2.0" value of
  Just "" -> True
  Just ('-':suffix) -> not (null suffix) && all isAlphaNum suffix
  _ -> False

symbol :: Id -> Maybe String
symbol value = case isFCallId_maybe value of
  Just (F.CCall (F.CCallSpec (F.StaticTarget _ name (Just unit) True) F.CCallConv F.PlayRisky))
    | byteStringUnit (unitString unit), unpackFS name `elem` map snd operations -> Just (unpackFS name)
  _ -> Nothing

variables :: CoreExpr -> [Id]
variables expression = case expression of
  Var value | isId value -> [value]
  App f x -> variables f ++ variables x
  Lam _ body -> variables body
  Let bindings body -> concatMap (variables . snd) (flattenBinds [bindings]) ++ variables body
  Case value _ _ alternatives -> variables value ++ concat [variables body | Alt _ _ body <- alternatives]
  Cast body _ -> variables body
  Tick _ body -> variables body
  _ -> []

prepareByteStringDecimal :: FilePath -> IO ()
prepareByteStringDecimal root = do
  let directory = "build/bytestring-decimal"
      source = "compiler/test-fixtures/ByteStringDecimalAudit.hs"
      execute = runLogged 180 root (directory </> "logs")
      manifest = root </> directory </> "manifest.json"
      oneLine result = case BS.lines (commandStdout result) of
        [value] -> BS.unpack value
        _ -> error "Expected one selected compiler line"
  createDirectoryIfMissing True (root </> directory </> "ghc")
  stale <- doesFileExist manifest
  if stale then removeFile manifest else pure ()
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  pkg <- maybe "ghc-pkg" id <$> lookupEnv "GHC_PKG"
  version <- execute "version" [] ghc ["--numeric-version"]
  unless (oneLine version == "9.14.1") (die "ByteString decimal fixture requires GHC 9.14.1")
  library <- execute "libdir" [] ghc ["--print-libdir"]
  imports <- execute "imports" [] pkg ["field", "bytestring", "import-dirs", "--simple-output"]
  owner <- execute "unit" [] pkg ["field", "bytestring", "id", "--simple-output"]
  unless (byteStringUnit (oneLine owner)) (die "Expected the pinned bytestring 0.12.2.0 owner")
  let interface = oneLine imports </> "Data/ByteString/Internal/Type.hi"
      entries = map fst operations
  oracle <- runGhc (Just (oneLine library)) $ do
    initial <- getSessionDynFlags
    env0 <- getSession
    (configured, _, _) <- parseDynamicFlags (hsc_logger env0) initial (map noLoc
      ["-O2", "-package", "bytestring", "-fno-external-interpreter", "-dcore-lint",
       "-odir", root </> directory </> "ghc", "-hidir", root </> directory </> "ghc"])
    _ <- setSessionDynFlags (gopt_unset configured Opt_IgnoreInterfacePragmas)
    flags <- getSessionDynFlags
    env <- getSession
    originals <- liftIO $ do
      raw <- readBinIface (targetProfile flags) (hsc_NC env) CheckHiWay QuietBinIFace interface
      unless (unitString (moduleUnit (mi_module raw)) == oneLine owner)
        (die "Wrong installed ByteString interface owner")
      types <- newIORef emptyTypeEnv
      let moduleOwner = mi_module raw
          old = hsc_type_env_vars env
          domain = case old of NoKnotVars -> []; KnotVars ms _ -> ms
          knots = KnotVars (moduleOwner : filter (/= moduleOwner) domain) $ \other ->
            if other == moduleOwner then Just types else lookupKnotVars old other
          tied = env { hsc_type_env_vars = knots }
      details <- initIfaceCheck (text "Original ByteString decimal fixture") tied (typecheckIface raw)
      writeIORef types (md_types details)
      pure $ nubBy (\a b -> symbol a == symbol b)
        [value | declaration <- typeEnvIds (md_types details),
                 nameModule_maybe (varName declaration) == Just moduleOwner,
                 Just body <- [maybeUnfoldingTemplate (realIdUnfolding declaration)],
                 value <- variables body, symbol value /= Nothing]
    liftIO $ unless (length originals == length operations) (die "Missing genuine ByteString decimal FCallIds")
    target <- guessTarget (root </> source) Nothing Nothing
    setTargets [target]
    graph <- depanal [] False
    summary <- case mgModSummaries graph of
      [value] -> pure value
      _ -> liftIO (die "Unexpected decimal fixture module graph")
    parsed <- parseModule summary
    checked <- typecheckModule parsed
    desugared <- desugarModule checked
    current <- getSession
    optimized <- liftIO $ hscSimplify current [] (coreModule desugared)
    let bindings = flattenBinds (mg_binds optimized)
        resolve expression = case expression of
          Var value | Just body <- lookup value bindings -> resolve body
          Cast body coercion -> Cast (resolve body) coercion
          Tick tick body -> Tick tick (resolve body)
          _ -> expression
        select name = case [(value, body) | (value, body) <- bindings,
                            getOccString value == name, isExternalName (varName value)] of
          [pair] -> pair
          _ -> error ("Missing decimal consumer " ++ name)
        specialize name targetName = case (select name, [value | value <- originals, symbol value == Just targetName]) of
          ((value, body), [original]) | Just (_, _, formal, _) <- splitFunTy_maybe (idType value),
                                     eqType formal (idType original) ->
            let applied = simpleOptExpr (initSimpleOpts flags) (App (resolve body) (Var original))
            in (setIdArity (setIdType (setIdInfo value vanillaIdInfo) (exprType applied)) (exprArity applied), applied)
          _ -> error ("Original decimal FCallId differs from typed consumer " ++ name)
        guests = [specialize name targetName | (name, targetName) <- operations]
        adapted = optimized { mg_binds = [NonRec value body | (value, body) <- guests],
          mg_exports = filter (\available -> availName available `elem` map (varName . fst) guests) (mg_exports optimized) }
    liftIO $ do
      serializeOptimizedCore flags ["unit-qualified"] adapted >>= writeFile (root </> directory </> "pre.json")
      (tidied, _) <- hscTidy current adapted
      serializePostTidyCore flags ["unit-qualified"] (cg_module tidied) (cg_tycons tidied)
        (cg_binds tidied) emptyIfaceForeign >>= writeFile (root </> directory </> "post.json")
    natives <- forM operations $ \(name, targetName) -> liftIO $ do
      let nativeName = case name of
            first : rest -> "native" ++ toUpper first : rest
            [] -> error "Empty decimal consumer name"
          (_, body) = specialize nativeName targetName
      (value, _, _) <- hscCompileCoreExpr current noSrcSpan (mkLets (mg_binds optimized) body)
      wormhole (hscInterp current) value
    liftIO $ case natives of
      [decimalValue, paddedValue] -> do
        let decimal = unsafeCoerce decimalValue :: Int64 -> Ptr Word8 -> IO (Ptr Word8)
            padded = unsafeCoerce paddedValue :: Int64 -> Ptr Word8 -> IO ()
            signedCases = [minBound,minBound+1,-1000000000000000000,-1000,-100,-10,-9,-1,0,1,9,10,99,100,999,1000,1000000000000000000,maxBound-1,maxBound]
            paddedCases = [0,1,9,10,99,100,999999999,1000000000,999999999999999999]
        fmap concat $ forM [("decimal",signedCases),("padded18",paddedCases)] $ \(name,values) ->
          forM values $ \value -> allocaBytes 48 $ \base -> do
            fillBytes base 165 48
            let address = base `plusPtr` 7
            end <- if name == ("decimal" :: String) then (`minusPtr` address) <$> decimal value address
              else padded value address >> pure 18
            unless (end >= 1 && end <= 20) (die "Native decimal end pointer escaped its contract")
            bytes <- Bytes.packCStringLen (base,48)
            pure (object ["entry" .= name,"input" .= value,"end" .= end,"bytes" .= hexBytes bytes])
      _ -> die "Missing compiled original decimal consumers"
  writeJson (root </> directory </> "oracle.json") (toJSON oracle)
  audits <- fmap concat $ forM ["pre", "post"] $ \stage -> forM entries $ \name ->
    execute (stage ++ "-audit-" ++ name) [] "python3" ["scripts/audit-core.py", "--entry", name,
      "--output", directory </> stage ++ "-" ++ name ++ ".audit.json", directory </> stage ++ ".json"]
  let commands = [version, library, imports, owner] ++ audits
  inputHashes <- hashes root [source, "thc.cabal", "test/haskell-fixtures/Main.hs", "test/haskell-fixtures/FixtureSupport.hs",
    "src/test/resources/core/original-bytestring-decimal-descriptors.json", "test/haskell-fixtures/ByteStringDecimalFixtures.hs",
    "compiler/THC/Plugin.hs", "compiler/THC/Interface.hs", "scripts/core_original_foreign.py",
    "scripts/audit-core.py", "scripts/core-capabilities.json", "src/main/java/thc/runtime/CoreByteStringDecimal.java",
    "src/main/java/thc/runtime/ByteStringDecimal.java", "src/main/java/thc/runtime/ByteStringDecimalOp.java",
    "src/main/java/thc/runtime/ByteStringDecimalExpression.java"]
  artifactHashes <- hashes root ([directory </> file | file <- ["pre.json", "post.json", "oracle.json"]] ++
    [directory </> stage ++ "-" ++ name ++ ".audit.json" | stage <- ["pre","post"], name <- entries] ++
    concatMap commandArtifacts commands)
  writeJson manifest $ object
    ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String), "entries" .= entries, "bytestringUnit" .= oneLine owner,
     "consumerKind" .= ("typed consumers specialized with genuine installed bytestring 0.12.2.0 FCallIds" :: String),
     "interface" .= interface, "installedArtifactsHashed" .= False, "inputHashes" .= inputHashes,
     "artifactHashes" .= artifactHashes, "commands" .= map commandRecord commands]
  putStrLn "bytestring-decimal: 2 original FCallIds, 28 native buffer observations and 4 strict audits"
