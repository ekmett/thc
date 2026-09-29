-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : ByteStringSortFixtures
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC 9.14.1 compiler API
--
-- Fixture acquisition support for byte string sort.
module ByteStringSortFixtures (prepareByteStringSort) where

import Control.Monad (forM, unless)
import Data.Aeson (object, toJSON, (.=))
import qualified Data.ByteString as Bytes
import qualified Data.ByteString.Char8 as BS
import Data.Char (isAlphaNum, toUpper)
import Data.IORef (newIORef, writeIORef)
import Data.List (nubBy, sort, stripPrefix)
import Data.Word (Word8)
import FixtureSupport
import Foreign.Marshal.Alloc (allocaBytes)
import Foreign.Marshal.Array (pokeArray)
import Foreign.Ptr (Ptr, castPtr, plusPtr)
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
import THC.Plugin (serializeOptimizedCoreCBD, serializePostTidyCoreCBD)
import Unsafe.Coerce (unsafeCoerce)

operations :: [(String, String)]
operations = [("sortBytes", "fps_sort")]

sortCases :: [(String, [Word8], Int, Int)]
sortCases = map whole
  [ ("empty", [])
  , ("singleton", [128])
  , ("high-bytes", [255,128,254,129,0,127])
  , ("duplicates", [7,7,0,255,128,7,0,255])
  , ("ascending20", [0..19])
  , ("descending20", reverse [0..19])
  , ("descending21", reverse [0..20])
  , ("boundary31", take 31 (cycle [255,0,128,127,1,254,128]))
  , ("all256", [0..255])
  , ("reversed256", reverse [0..255])
  , ("permuted256", [fromIntegral (mod (i * 73 + 19) 256) | i <- [0..255 :: Int]])
  , ("duplicates512", take 512 (cycle [255,0,128,127,1,254,128]))
  ] ++
  [ ("middle-slice", reverse [0..31], 5, 20)
  , ("high-byte-slice", [17,23,255,128,254,0,127,129,42,11], 2, 6)
  , ("empty-interior-slice", [255,0,128,7], 2, 0)
  , ("singleton-interior-slice", [255,0,128,7], 2, 1)
  , ("end-slice", reverse [0..31], 12, 20)
  ]
  where whole (name, values) = (name, values, 0, length values)

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

prepareByteStringSort :: FilePath -> IO ()
prepareByteStringSort root = do
  let directory = "build/bytestring-sort"
      source = "t/fixtures/compiler/ByteStringSortAudit.hs"
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
  unless (oneLine version == "9.14.1") (die "ByteString sort fixture requires GHC 9.14.1")
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
      details <- initIfaceCheck (text "Original ByteString sort fixture") tied (typecheckIface raw)
      writeIORef types (md_types details)
      pure $ nubBy (\a b -> symbol a == symbol b)
        [value | declaration <- typeEnvIds (md_types details),
                 nameModule_maybe (varName declaration) == Just moduleOwner,
                 Just body <- [maybeUnfoldingTemplate (realIdUnfolding declaration)],
                 value <- variables body, symbol value /= Nothing]
    liftIO $ unless (length originals == length operations) (die "Missing genuine ByteString sort FCallIds")
    target <- guessTarget (root </> source) Nothing Nothing
    setTargets [target]
    graph <- depanal [] False
    summary <- case mgModSummaries graph of
      [value] -> pure value
      _ -> liftIO (die "Unexpected sort fixture module graph")
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
          _ -> error ("Missing sort consumer " ++ name)
        specialize name targetName = case (select name, [value | value <- originals, symbol value == Just targetName]) of
          ((value, body), [original]) | Just (_, _, formal, _) <- splitFunTy_maybe (idType value),
                                     eqType formal (idType original) ->
            let applied = simpleOptExpr (initSimpleOpts flags) (App (resolve body) (Var original))
            in (setIdArity (setIdType (setIdInfo value vanillaIdInfo) (exprType applied)) (exprArity applied), applied)
          _ -> error ("Original sort FCallId differs from typed consumer " ++ name)
        guests = [specialize name targetName | (name, targetName) <- operations]
        adapted = optimized { mg_binds = [NonRec value body | (value, body) <- guests],
          mg_exports = filter (\available -> availName available `elem` map (varName . fst) guests) (mg_exports optimized) }
    liftIO $ do
      serializeOptimizedCoreCBD flags ["unit-qualified"] adapted >>= BS.writeFile (root </> directory </> "pre.cbd")
      (tidied, _) <- hscTidy current adapted
      serializePostTidyCoreCBD flags ["unit-qualified"] (cg_module tidied) (cg_tycons tidied)
        (cg_binds tidied) emptyIfaceForeign >>= BS.writeFile (root </> directory </> "post.cbd")
    natives <- forM operations $ \(name, targetName) -> liftIO $ do
      let nativeName = case name of
            first : rest -> "native" ++ toUpper first : rest
            [] -> error "Empty sort consumer name"
          (_, body) = specialize nativeName targetName
      (value, _, _) <- hscCompileCoreExpr current noSrcSpan (mkLets (mg_binds optimized) body)
      wormhole (hscInterp current) value
    liftIO $ case natives of
      [nativeValue] -> do
        let native = unsafeCoerce nativeValue :: Ptr Word8 -> Int -> IO ()
        forM sortCases $ \(name, payload, start, byteCount) -> do
          let inputBytes = replicate 7 165 ++ payload ++ replicate 9 90
              offset = 7 + start
              total = length inputBytes
              expected = take offset inputBytes ++ sort (take byteCount (drop offset inputBytes)) ++
                drop (offset + byteCount) inputBytes
          unless (start >= 0 && byteCount >= 0 && start + byteCount <= length payload)
            (die "Invalid native ByteString sort slice")
          allocaBytes total $ \base -> do
            pokeArray base inputBytes
            native (plusPtr base offset) byteCount
            bytes <- Bytes.packCStringLen (castPtr base, total)
            unless (Bytes.unpack bytes == expected)
              (die ("Original fps_sort disagrees with unsigned order or modified sentinels: " ++ name))
            pure (object ["entry" .= ("sortBytes" :: String), "case" .= name,
              "input" .= hexBytes (Bytes.pack inputBytes), "offset" .= offset, "count" .= byteCount,
              "result" .= byteCount, "bytes" .= hexBytes bytes])
      _ -> die "Missing compiled original sort consumer"
  writeJson (root </> directory </> "oracle.json") (toJSON oracle)
  audits <- fmap concat $ forM ["pre", "post"] $ \stage -> forM entries $ \name ->
    execute (stage ++ "-audit-" ++ name) [] "python3" ["bin/audit-core.py", "--entry", "main:ByteStringSortAudit." ++ name,
      "--output", directory </> stage ++ "-" ++ name ++ ".audit.json", directory </> stage ++ ".cbd"]
  let commands = [version, library, imports, owner] ++ audits
  inputHashes <- hashes root [source, "thc.cabal", "t/haskell-fixtures/Main.hs", "t/haskell-fixtures/FixtureSupport.hs",
    "src/test/resources/core/original-bytestring-sort-descriptor.json", "t/haskell-fixtures/ByteStringSortFixtures.hs",
    "src/compiler/THC/Plugin.hs", "src/compiler/THC/Interface.hs", "bin/core_original_foreign.py",
    "bin/audit-core.py", "bin/core-capabilities.json", "src/main/java/thc/runtime/CoreByteStringSort.java",
    "src/main/java/thc/runtime/ByteStringSort.java", "src/main/java/thc/runtime/ByteStringSortExpression.java"]
  artifactHashes <- hashes root ([directory </> file | file <- ["pre.cbd", "post.cbd", "oracle.json"]] ++
    [directory </> stage ++ "-" ++ name ++ ".audit.json" | stage <- ["pre","post"], name <- entries] ++
    concatMap commandArtifacts commands)
  writeJson manifest $ object
    ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String), "entries" .= entries, "bytestringUnit" .= oneLine owner,
     "consumerKind" .= ("typed consumers specialized with genuine installed bytestring 0.12.2.0 FCallIds" :: String),
     "interface" .= interface, "installedArtifactsHashed" .= False, "inputHashes" .= inputHashes,
     "artifactHashes" .= artifactHashes, "commands" .= map commandRecord commands]
  putStrLn ("bytestring-sort: 1 original FCallId, " ++ show (length sortCases) ++ " native buffer observations and 2 strict audits")
