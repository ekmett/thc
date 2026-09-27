-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}
module OriginalTimeClockFixtures (prepareOriginalTimeClock) where

import Control.Monad (forM, unless)
import Data.Aeson (Value, object, (.=))
import qualified Data.ByteString.Char8 as BS
import Data.Int (Int64)
import Data.List (nub, nubBy, isSuffixOf)
import Data.Word (Word8)
import FixtureSupport
import Foreign.C.Error (Errno(..), getErrno)
import System.Posix.Internals (c_close)
import Foreign.Marshal.Alloc (allocaBytes)
import Foreign.Marshal.Array (peekArray)
import Foreign.Marshal.Utils (fillBytes)
import Foreign.Ptr (Ptr, plusPtr, nullPtr)
import Foreign.Storable (peekByteOff)
import GHC hiding (entry, exprType)
import GHC.Plugins hiding (line, (<>))
import GHC.Core.TyCo.Compare (eqType)
import GHC.Core.SimpleOpt (simpleOptExpr)
import GHC.Core.Opt.Arity (exprArity)
import GHC.Driver.Config (initSimpleOpts)
import GHC.Driver.Main (hscSimplify, hscTidy, hscCompileCoreExpr)
import GHC.Iface.Binary
import GHC.Runtime.Interpreter (wormhole)
import GHC.Types.Avail (availName)
import qualified GHC.Types.ForeignCall as F
import GHC.Unit.Module.WholeCoreBindings (emptyIfaceForeign)
import System.Directory (createDirectoryIfMissing)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>))
import Text.Read (readMaybe)
import THC.Driver.ForeignBitcode (linkClockGetTime, timeClockHeaders)
import THC.Interface (loadInterfaceCore, interfaceBindings, interfaceCoreJSONBytes)
import THC.Plugin (serializeOptimizedCore, serializePostTidyCore)
import Unsafe.Coerce (unsafeCoerce)

variables :: CoreExpr -> [Id]
variables expression = case expression of
  Var v | isId v -> [v]
  App f x -> variables f ++ variables x
  Lam _ body -> variables body
  Let binds body -> concatMap (variables . snd) (flattenBinds [binds]) ++ variables body
  Case value _ _ alts -> variables value ++ concat [variables body | Alt _ _ body <- alts]
  Cast body _ -> variables body
  Tick _ body -> variables body
  _ -> []

prepareOriginalTimeClock :: FilePath -> IO ()
prepareOriginalTimeClock root = do
  let directory = "build/original-time-clock"
      source = "compiler/test-fixtures/OriginalTimeClockAudit.hs"
      execute = runLogged 180 root (directory </> "logs")
      oneLine result = case lines (BS.unpack (commandStdout result)) of
        [value] -> value
        _ -> error "Expected one selected compiler line"
  createDirectoryIfMissing True (root </> directory </> "ghc")
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  pkg <- maybe "ghc-pkg" id <$> lookupEnv "GHC_PKG"
  version <- execute "version" [] ghc ["--numeric-version"]
  unless (oneLine version == "9.14.1") (die "Original time clocks require GHC9.14.1")
  library <- execute "libdir" [] ghc ["--print-libdir"]
  info <- execute "info" [] ghc ["--info"]
  platform <- case readMaybe (BS.unpack (commandStdout info)) :: Maybe [(String,String)] of
    Just fields | lookup "target word size" fields == Just "8",
      Just target <- lookup "target platform string" fields, "-linux" `isSuffixOf` target -> pure target
    _ -> die "Original time clocks require a selected native Linux64 GHC"
  imports <- execute "imports" [] pkg ["field","time","import-dirs","--simple-output"]
  include <- execute "includes" [] pkg ["field","time","include-dirs","--simple-output"]
  identity <- execute "unit" [] pkg ["field","time","id","--simple-output"]
  baseImports <- execute "base-imports" [] pkg ["field","base","import-dirs","--simple-output"]
  let unit = oneLine identity
      libdir = oneLine library
      includes = words (BS.unpack (commandStdout include))
      interface = oneLine imports </> "Data/Time/Clock/Internal/CTimespec.hi"
      baseInterface = oneLine baseImports </> "System/CPUTime/Posix/ClockGetTime.hi"
      calls = [("Id","HSzuCLOCKzuREALTIME"),("Resolution","clockzugetres"),("Time","clockzugettime"),("Constant","clock_REALTIME")]
      originalSymbol v = case isFCallId_maybe v of
        Just (F.CCall (F.CCallSpec (F.StaticTarget _ name (Just owner) True) F.CApiConv F.PlayRisky))
          | unitString owner == unit -> Just (unpackFS name)
        _ -> Nothing
  headersBefore <- timeClockHeaders libdir includes
  (clock, rows, signatures) <- runGhc (Just libdir) $ do
    initial <- getSessionDynFlags
    env0 <- getSession
    (configured,_,_) <- parseDynamicFlags (hsc_logger env0) initial (map noLoc
      ["-O2","-package","time","-package","base","-package","unix","-fno-external-interpreter","-dcore-lint",
       "-odir",root </> directory </> "ghc","-hidir",root </> directory </> "ghc"])
    _ <- setSessionDynFlags (gopt_unset configured Opt_IgnoreInterfacePragmas)
    flags <- getSessionDynFlags
    env <- getSession
    (originals, constant) <- liftIO $ do
      raw <- readBinIface (targetProfile flags) (hsc_NC env) CheckHiWay QuietBinIFace interface
      unless (unitString (moduleUnit (mi_module raw)) == unit) (die "Wrong original time interface owner")
      actual <- loadInterfaceCore env (mi_module raw) interface >>= maybe (die "Original time clocks require complete installed Core") pure
      bytes <- interfaceCoreJSONBytes ["unit-qualified"] actual
      BS.writeFile (root </> directory </> "original.json") bytes
      linked <- linkClockGetTime libdir includes (root </> directory </> "ghc") platform unit
        "Data.Time.Clock.Internal.CTimespec" bytes
      BS.writeFile (root </> directory </> "linked.json") linked
      baseRaw <- readBinIface (targetProfile flags) (hsc_NC env) CheckHiWay QuietBinIFace baseInterface
      baseActual <- loadInterfaceCore env (mi_module baseRaw) baseInterface >>= maybe (die "Base coexistence requires complete Core") pure
      baseBytes <- interfaceCoreJSONBytes ["unit-qualified"] baseActual
      BS.writeFile (root </> directory </> "base-original.json") baseBytes
      baseLinked <- linkClockGetTime libdir [] (root </> directory </> "ghc") platform
        (unitString (moduleUnit (mi_module baseRaw))) "System.CPUTime.Posix.ClockGetTime" baseBytes
      BS.writeFile (root </> directory </> "base-linked.json") baseLinked
      constant <- case [v | (v,_) <- flattenBinds (interfaceBindings actual), getOccString v == "clock_REALTIME"] of
        [v] -> pure v
        _ -> die "Missing unique original clock_REALTIME value binding"
      pure (nubBy (\a b -> originalSymbol a == originalSymbol b)
        [v | (_,body) <- flattenBinds (interfaceBindings actual), v <- variables body, originalSymbol v /= Nothing], constant)
    liftIO $ unless (length originals == 3) (die "Original time interface does not contain exactly three CAPI declarations")
    file <- guessTarget (root </> source) Nothing Nothing
    setTargets [file]
    graph <- depanal [] False
    summary <- case mgModSummaries graph of [ms] -> pure ms; _ -> liftIO (die "Unexpected time fixture graph")
    parsed <- parseModule summary
    checked <- typecheckModule parsed
    desugared <- desugarModule checked
    current <- getSession
    optimized <- liftIO $ hscSimplify current [] (coreModule desugared)
    let bindings = flattenBinds (mg_binds optimized)
        resolve expression = case expression of
          Var v | Just body <- lookup v bindings -> resolve body
          Cast body coercion -> Cast (resolve body) coercion
          Tick tick body -> Tick tick (resolve body)
          _ -> expression
        specialize prefix (consumer,suffix) = case
            ([(v,body) | (v,body) <- bindings, getOccString v == prefix ++ consumer, isExternalName (varName v)],
             if consumer == "Constant" then [constant] else [v | v <- originals, maybe False (isSuffixOf suffix) (originalSymbol v)]) of
          ([(v,body)],[original]) | Just (_,_,formal,_) <- splitFunTy_maybe (idType v), eqType formal (idType original) ->
            let applied = simpleOptExpr (initSimpleOpts flags) (App (resolve body) (Var original))
            in (setIdArity (setIdType (setIdInfo v vanillaIdInfo) (exprType applied)) (exprArity applied), applied)
          _ -> error ("Original time FCallId differs from typed consumer " ++ prefix ++ consumer)
        selected = map (specialize "original") calls
        replacement (v,body) = case [pair | pair@(key,_) <- selected, key == v] of
          [pair] -> pair
          [] -> (v,body)
          _ -> error "Multiple specialized clock roots"
        groups = [case group of NonRec v body -> uncurry NonRec (replacement (v,body))
                                Rec pairs -> Rec (map replacement pairs) | group <- mg_binds optimized]
        close required =
          let included = [group | group <- groups, any ((`elem` required) . fst) (flattenBinds [group])]
              next = nub (required ++ [v | group <- included, (key,body) <- flattenBinds [group],
                v <- key : variables body, v `elem` map fst bindings])
          in if length next == length required then included else close next
        adapted = optimized { mg_binds = close (map fst selected),
          mg_exports = filter (\a -> any (\c -> varName (fst (specialize "original" c)) == availName a) calls) (mg_exports optimized) }
        compile call = do
          let (_,body) = specialize "native" call
          (value,_,_) <- hscCompileCoreExpr current noSrcSpan (mkLets (mg_binds optimized) body)
          wormhole (hscInterp current) value
    liftIO $ do
      let retained = map fst (flattenBinds (mg_binds adapted))
          omitted = [getOccString variable | (_,body) <- flattenBinds (mg_binds adapted),
            variable <- variables body, variable `elem` map fst bindings, variable `notElem` retained]
      unless (null omitted) (die ("Clock consumer specialization omitted local dependencies: " ++ show omitted))
      serializeOptimizedCore flags ["unit-qualified"] adapted >>= writeFile (root </> directory </> "specialized-closed.json")
      simplified <- hscSimplify current [] adapted
      serializeOptimizedCore flags ["unit-qualified"] simplified >>= writeFile (root </> directory </> "pre.json")
      (tidied,_) <- hscTidy current simplified
      serializePostTidyCore flags ["unit-qualified"] (cg_module tidied) (cg_tycons tidied)
        (cg_binds tidied) emptyIfaceForeign >>= writeFile (root </> directory </> "post.json")
      identifier <- compile ("Constant", "clock_REALTIME")
      realtime <- (unsafeCoerce identifier :: Int -> IO Int) 0
      observations <- fmap concat $ forM [call | call@(name,_) <- calls, name `elem` ["Resolution","Time"]] $ \call@(name,_) -> do
        function <- compile call
        let invoke = unsafeCoerce function :: Int -> Ptr Word8 -> IO Int
        forM [(clockId, absent) | clockId <- [realtime,-1,2147483647], absent <- if name == "Resolution" then [False,True] else [False]] $ \(clockId,absent) ->
          allocaBytes 32 $ \buffer -> do
            fillBytes buffer 0x5a 32
            _ <- c_close (-1)
            Errno seedErrno <- getErrno
            status <- invoke clockId (if absent then nullPtr else buffer `plusPtr` 8)
            Errno errno <- getErrno
            image <- peekArray 32 buffer :: IO [Word8]
            seconds <- peekByteOff buffer 8 :: IO Int64
            nanos <- peekByteOff buffer 16 :: IO Int64
            unless (status == 0 || status == -1) (die "Unexpected original native clock status")
            unless (status /= 0 || absent || seconds >= 0 && nanos >= 0 && nanos < 1000000000) (die "Native timespec invariant failed")
            unless (take 8 image == replicate 8 0x5a && drop 24 image == replicate 8 0x5a) (die "Native clock guard overwritten")
            pure (object ["entry" .= ("original" ++ name),"clock" .= clockId,"null" .= absent,
              "status" .= status,"errno" .= (fromIntegral errno :: Int),"seedErrno" .= (fromIntegral seedErrno :: Int),"seconds" .= seconds,"nanos" .= nanos,
              "image" .= image,"failedImageUnchanged" .= (image == replicate 32 0x5a)])
      pure (realtime, observations, [(name,showSDoc flags (ppr (idType v))) | v <- originals, Just name <- [originalSymbol v]])
  headersAfter <- timeClockHeaders libdir includes
  unless (headersBefore == headersAfter) (die "Selected clock headers changed during fixture preparation")
  writeJson (root </> directory </> "oracle.json") $ object ["realtime" .= clock,"rows" .= (rows :: [Value])]
  audits <- forM [(stage,entry) | stage <- ["pre","post"], entry <- ["originalId","originalConstant","originalResolution","originalTime"]] $ \(stage,entry) ->
    runLoggedExpect (if entry == "originalId" then 1 else 0) 180 root (directory </> "logs") (stage ++ "-" ++ entry) [] "python3" ["scripts/audit-core.py","--entry",entry,
      "--output",directory </> stage ++ "-" ++ entry ++ ".audit.json",directory </> "linked.json",directory </> stage ++ ".json"]
  inputHashes <- hashes root [source,"test/haskell-fixtures/OriginalTimeClockFixtures.hs","src/THC/Driver/ForeignBitcode.hs",
    "compiler/THC/Plugin.hs","compiler/THC/Interface.hs","scripts/core_package_manifest.py","scripts/audit-core.py"]
  artifactHashes <- hashes root ([directory </> name | name <- ["oracle.json","original.json","linked.json","base-original.json","base-linked.json","specialized-closed.json","pre.json","post.json"]] ++
    [directory </> stage ++ "-" ++ entry ++ ".audit.json" | stage <- ["pre","post"], entry <- ["originalId","originalConstant","originalResolution","originalTime"]] ++
    concatMap commandArtifacts ([version,library,info,imports,include,identity,baseImports] ++ audits))
  interfaceHashes <- hashes root [interface,baseInterface]
  writeJson (root </> directory </> "manifest.json") $ object ["schema" .= (1 :: Int),"unit" .= unit,"nativeRows" .= length rows,
    "signatures" .= signatures,"headerHashes" .= headersAfter,"interfaceHashes" .= interfaceHashes,
    "inputHashes" .= inputHashes,"artifactHashes" .= artifactHashes]
  putStrLn "original-time-clock: genuine time and base CAPI, nine native observations, six strict positive and two first-class FCallId negative audits"
