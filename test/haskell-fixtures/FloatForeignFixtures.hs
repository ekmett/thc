-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : FloatForeignFixtures
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC 9.14.1 compiler API
--
-- Fixture acquisition support for float foreign.
module FloatForeignFixtures (prepareFloatForeign) where

import Control.Monad (forM, unless)
import Data.Aeson (object, (.=))
import qualified Data.ByteString.Char8 as BS
import Data.Char (toUpper)
import Data.List (nubBy)
import FixtureSupport
import GHC hiding (entry, exprType)
import GHC.Plugins
import GHC.Core.TyCo.Compare (eqType)
import GHC.Core.SimpleOpt (simpleOptExpr)
import GHC.Core.Opt.Arity (exprArity)
import GHC.Driver.Config (initSimpleOpts)
import GHC.Driver.Main (hscSimplify, hscTidy, hscCompileCoreExpr)
import GHC.Iface.Binary
import GHC.Runtime.Interpreter (wormhole)
import GHC.Unit.Module.WholeCoreBindings (emptyIfaceForeign)
import GHC.Types.Avail (availName)
import qualified GHC.Types.ForeignCall as F
import System.Directory (createDirectoryIfMissing, doesFileExist, removeFile)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>))
import THC.Interface (loadInterfaceCore, interfaceBindings)
import THC.Plugin (serializeOptimizedCore, serializePostTidyCore)
import Unsafe.Coerce (unsafeCoerce)

operations :: [(String, String, Bool)]
operations =
  [("floatNaN", "isFloatNaN", True),
   ("floatInfinite", "isFloatInfinite", True),
   ("floatFinite", "isFloatFinite", True),
   ("floatDenormalized", "isFloatDenormalized", True),
   ("floatNegativeZero", "isFloatNegativeZero", True),
   ("floatRound", "rintFloat", True),
   ("doubleNaN", "isDoubleNaN", False),
   ("doubleInfinite", "isDoubleInfinite", False),
   ("doubleFinite", "isDoubleFinite", False),
   ("doubleDenormalized", "isDoubleDenormalized", False),
   ("doubleNegativeZero", "isDoubleNegativeZero", False),
   ("doubleRound", "rintDouble", False)]

symbol :: Id -> Maybe String
symbol value = case isFCallId_maybe value of
  Just (F.CCall (F.CCallSpec (F.StaticTarget _ name (Just unit) True) F.CCallConv F.PlayRisky))
    | unitString unit == "ghc-internal", unpackFS name `elem` [target | (_, target, _) <- operations] ->
        Just (unpackFS name)
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

-- Native bit patterns include both zeros, normal/subnormal boundaries, signed
-- infinities, quiet/signalling NaNs with payloads, half ties and their neighbours.
inputs :: Bool -> [Int]
inputs single = map fromInteger $ if single then
  [0,0x80000000,1,0x80000001,0x007fffff,0x807fffff,0x00800000,0x80800000,
   0x3effffff,0xbeffffff,0x3f000000,0xbf000000,0x3f000001,0xbf000001,
   0x3fc00000,0xbfc00000,0x40200000,0xc0200000,0x40600000,0xc0600000,
   0x4affffff,0xcaffffff,0x4b000000,0xcb000000,0x7f7fffff,0xff7fffff,
   0x7f800000,0xff800000,0x7fc00001,0xffc00001,0x7f800001,0xff800001]
  else
  [0,0x8000000000000000,1,0x8000000000000001,0x000fffffffffffff,0x800fffffffffffff,
   0x0010000000000000,0x8010000000000000,0x3fdfffffffffffff,0xbfdfffffffffffff,
   0x3fe0000000000000,0xbfe0000000000000,0x3fe0000000000001,0xbfe0000000000001,
   0x3ff8000000000000,0xbff8000000000000,0x4004000000000000,0xc004000000000000,
   0x400c000000000000,0xc00c000000000000,0x432fffffffffffff,0xc32fffffffffffff,
   0x4330000000000000,0xc330000000000000,0x7fefffffffffffff,0xffefffffffffffff,
   0x7ff0000000000000,0xfff0000000000000,0x7ff8000000000001,0xfff8000000000001,
   0x7ff0000000000001,0xfff0000000000001]

prepareFloatForeign :: FilePath -> IO ()
prepareFloatForeign root = do
  let directory = "build/float-foreign"
      source = "compiler/test-fixtures/FloatForeignAudit.hs"
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
  unless (oneLine version == "9.14.1") (die "Floating FFI fixture requires GHC 9.14.1")
  library <- execute "libdir" [] ghc ["--print-libdir"]
  imports <- execute "imports" [] pkg ["field", "ghc-internal", "import-dirs", "--simple-output"]
  let interfaces = map (oneLine imports </>)
        ["GHC/Internal/Float.hi", "GHC/Internal/Float/RealFracMethods.hi"]
      entries = [name | (name, _, _) <- operations]
  rows <- runGhc (Just (oneLine library)) $ do
    initial <- getSessionDynFlags
    env0 <- getSession
    (configured, _, _) <- parseDynamicFlags (hsc_logger env0) initial (map noLoc
      ["-O2", "-fno-external-interpreter", "-dcore-lint",
       "-odir", root </> directory </> "ghc", "-hidir", root </> directory </> "ghc"])
    _ <- setSessionDynFlags (gopt_unset configured Opt_IgnoreInterfacePragmas)
    flags <- getSessionDynFlags
    env <- getSession
    originals <- liftIO $ fmap (nubBy (\a b -> symbol a == symbol b) . concat) $ forM interfaces $ \path -> do
      raw <- readBinIface (targetProfile flags) (hsc_NC env) CheckHiWay QuietBinIFace path
      unless (unitString (moduleUnit (mi_module raw)) == "ghc-internal")
        (die "Wrong installed floating interface owner")
      recovered <- loadInterfaceCore env (mi_module raw) path
      actual <- maybe (die "Installed floating module lacks complete Core") pure recovered
      pure [value | (_, body) <- flattenBinds (interfaceBindings actual), value <- variables body, symbol value /= Nothing]
    liftIO $ unless (length originals == length operations) (die "Missing genuine floating FCallIds")
    target <- guessTarget (root </> source) Nothing Nothing
    setTargets [target]
    graph <- depanal [] False
    summary <- case mgModSummaries graph of
      [value] -> pure value
      _ -> liftIO (die "Unexpected floating fixture module graph")
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
          _ -> error ("Missing floating test consumer " ++ name)
        specialize name targetName = case (select name, [value | value <- originals, symbol value == Just targetName]) of
          ((value, body), [original]) | Just (_, _, formal, _) <- splitFunTy_maybe (idType value),
                                     eqType formal (idType original) ->
            let applied = simpleOptExpr (initSimpleOpts flags) (App (resolve body) (Var original))
            in (setIdArity (setIdType (setIdInfo value vanillaIdInfo) (exprType applied)) (exprArity applied), applied)
          _ -> error ("Original floating FCallId differs from typed consumer " ++ name)
        guests = [specialize name targetName | (name, targetName, _) <- operations]
        adapted = optimized { mg_binds = [NonRec value body | (value, body) <- guests],
          mg_exports = filter (\available -> availName available `elem` map (varName . fst) guests) (mg_exports optimized) }
    liftIO $ do
      serializeOptimizedCore flags ["unit-qualified"] adapted >>= writeFile (root </> directory </> "pre.json")
      (tidied, _) <- hscTidy current adapted
      serializePostTidyCore flags ["unit-qualified"] (cg_module tidied) (cg_tycons tidied)
        (cg_binds tidied) emptyIfaceForeign >>= writeFile (root </> directory </> "post.json")
    fmap concat $ forM operations $ \(name, targetName, single) -> liftIO $ do
      let nativeName = case name of
            first : rest -> "native" ++ toUpper first : rest
            [] -> error "Empty floating consumer name"
          (_, body) = specialize nativeName targetName
      (value, _, _) <- hscCompileCoreExpr current noSrcSpan (mkLets (mg_binds optimized) body)
      original <- wormhole (hscInterp current) value
      let native = unsafeCoerce original :: Int -> Int
      forM (inputs single) $ \bits -> do
        let answer = native bits
        answer `seq` pure (name ++ "\t" ++ show bits ++ "\t" ++ show answer)
  writeFile (root </> directory </> "oracle.tsv") (unlines rows)
  audits <- fmap concat $ forM ["pre", "post"] $ \stage -> forM entries $ \name ->
    execute (stage ++ "-audit-" ++ name) [] "python3" ["scripts/audit-core.py", "--entry", name,
      "--output", directory </> stage ++ "-" ++ name ++ ".audit.json", directory </> stage ++ ".json"]
  let commands = [version, library, imports] ++ audits
  inputHashes <- hashes root [source, "test/haskell-fixtures/FloatForeignFixtures.hs",
    "compiler/THC/Plugin.hs", "compiler/THC/Interface.hs", "scripts/core_original_foreign.py",
    "scripts/audit-core.py", "scripts/core-capabilities.json"]
  artifactHashes <- hashes root ([directory </> file | file <- ["pre.json", "post.json", "oracle.tsv"]] ++
    concatMap commandArtifacts commands)
  interfaceHashes <- hashes root interfaces
  writeJson manifest $ object
    ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String), "entries" .= entries,
     "consumerKind" .= ("typed raw-bit consumers specialized with genuine installed floating FCallIds" :: String),
     "interfaceHashes" .= interfaceHashes, "inputHashes" .= inputHashes,
     "artifactHashes" .= artifactHashes, "commands" .= map commandRecord commands]
  putStrLn "float-foreign: 12 original FCallIds, 384 native raw-bit cases and 24 strict audits"
