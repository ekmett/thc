-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

module THC.Driver.Wired
  ( WiredArtifacts(..), bootSources, moduleSources, sourceHashes, exportPinnedCore ) where

import Control.Monad (forM, forM_, when)
import qualified Data.ByteString.Lazy.Char8 as BL
import Data.Aeson (encode)
import Data.List (isSuffixOf)
import System.Directory (createDirectoryIfMissing, createFileLink, doesDirectoryExist, doesFileExist,
                         findExecutable, listDirectory, removeFile)
import System.Exit (ExitCode(..))
import System.FilePath ((</>), makeRelative, replaceExtension, takeDirectory, takeExtension)
import System.Process (CreateProcess(..), createProcess, proc, readProcess, rawSystem, waitForProcess)

-- These are the original GHC 9.14.1 sources. Boot interfaces are compiled
-- first; the installed ghc-internal dynamic interfaces fill the remaining
-- dependencies without changing the installed package database.
bootSources :: [FilePath]
bootSources =
  [ "GHC/Internal/Num.hs-boot"
  , "GHC/Internal/Enum.hs-boot"
  , "GHC/Internal/Real.hs-boot"
  , "GHC/Internal/Fingerprint.hs-boot"
  , "GHC/Internal/Exception/Type.hs-boot"
  , "GHC/Internal/Stack.hs-boot"
  , "GHC/Internal/IO/Handle/Types.hs-boot"
  , "GHC/Internal/IO/Exception.hs-boot"
  , "GHC/Internal/IO.hs-boot"
  , "GHC/Internal/Exception/Backtrace.hs-boot"
  , "GHC/Internal/Exception.hs-boot"
  ]

-- The order is the proven private-overlay compile order. Only these modules
-- are put into the immutable ZIP; generated Core is never checked into Git.
moduleSources :: [(FilePath, String)]
moduleSources =
  [ ("GHC/Internal/Data/Typeable/Internal.hs", "GHC.Internal.Data.Typeable.Internal")
  , ("GHC/Internal/Types.hs", "GHC.Internal.Types")
  , ("GHC/Internal/Fingerprint.hs", "GHC.Internal.Fingerprint")
  , ("GHC/Internal/Arr.hs", "GHC.Internal.Arr")
  , ("GHC/Internal/Ix.hs", "GHC.Internal.Ix")
  , ("GHC/Internal/List.hs", "GHC.Internal.List")
  , ("GHC/Internal/Data/Tuple.hs", "GHC.Internal.Data.Tuple")
  , ("GHC/Internal/Foreign/Storable.hs", "GHC.Internal.Foreign.Storable")
  , ("GHC/Internal/Foreign/Marshal/Alloc.hs", "GHC.Internal.Foreign.Marshal.Alloc")
  , ("GHC/Internal/Bignum/Natural.hs", "GHC.Internal.Bignum.Natural")
  , ("GHC/Internal/Base.hs", "GHC.Internal.Base")
  , ("GHC/Internal/IO/Unsafe.hs", "GHC.Internal.IO.Unsafe")
  , ("GHC/Internal/Show.hs", "GHC.Internal.Show")
  , ("GHC/Internal/Err.hs", "GHC.Internal.Err")
  , ("GHC/Internal/Enum.hs", "GHC.Internal.Enum")
  , ("GHC/Internal/Ptr.hs", "GHC.Internal.Ptr")
  , ("GHC/Internal/Data/Either.hs", "GHC.Internal.Data.Either")
  , ("GHC/Internal/Word.hs", "GHC.Internal.Word")
  , ("GHC/Internal/ClosureTypes.hs", "GHC.Internal.ClosureTypes")
  , ("GHC/Internal/Heap/Constants.hsc", "GHC.Internal.Heap.Constants")
  , ("GHC/Internal/Heap/InfoTable/Types.hsc", "GHC.Internal.Heap.InfoTable.Types")
  , ("GHC/Internal/Heap/InfoTable.hsc", "GHC.Internal.Heap.InfoTable")
  , ("GHC/Internal/Heap/Closures.hs", "GHC.Internal.Heap.Closures")
  , ("GHC/Internal/Stack/Constants.hsc", "GHC.Internal.Stack.Constants")
  , ("GHC/Internal/Stack/Annotation.hs", "GHC.Internal.Stack.Annotation")
  , ("GHC/Internal/Stack/CloneStack.hs", "GHC.Internal.Stack.CloneStack")
  , ("GHC/Internal/ForeignPtr.hs", "GHC.Internal.ForeignPtr")
  , ("GHC/Internal/Foreign/C/String/Encoding.hs", "GHC.Internal.Foreign.C.String.Encoding")
  , ("GHC/Internal/IO/Encoding/UTF8.hs", "GHC.Internal.IO.Encoding.UTF8")
  , ("GHC/Internal/InfoProv/Types.hsc", "GHC.Internal.InfoProv.Types")
  , ("GHC/Internal/Data/Maybe.hs", "GHC.Internal.Data.Maybe")
  , ("GHC/Internal/Data/OldList.hs", "GHC.Internal.Data.OldList")
  , ("GHC/Internal/Stack/Types.hs", "GHC.Internal.Stack.Types")
  , ("GHC/Internal/Stack/CCS.hsc", "GHC.Internal.Stack.CCS")
  , ("GHC/Internal/ExecutionStack/Internal.hsc", "GHC.Internal.ExecutionStack.Internal")
  , ("GHC/Internal/Stack/Decode.hs", "GHC.Internal.Stack.Decode")
  , ("GHC/Internal/Exception/Type.hs", "GHC.Internal.Exception.Type")
  , ("GHC/Internal/IO/Exception.hs", "GHC.Internal.IO.Exception")
  , ("GHC/Internal/IO.hs", "GHC.Internal.IO")
  , ("GHC/Internal/Control/Monad/Fail.hs", "GHC.Internal.Control.Monad.Fail")
  , ("GHC/Internal/Exception/Context.hs", "GHC.Internal.Exception.Context")
  , ("GHC/Internal/Exception.hs", "GHC.Internal.Exception")
  , ("GHC/Internal/Stack.hs", "GHC.Internal.Stack")
  , ("GHC/Internal/IO/Handle/Types.hs", "GHC.Internal.IO.Handle.Types")
  , ("GHC/Internal/IO/Encoding.hs", "GHC.Internal.IO.Encoding")
  , ("GHC/Internal/Exception/Backtrace.hs", "GHC.Internal.Exception.Backtrace")
  , ("GHC/Internal/CString.hs", "GHC.Internal.CString")
  ]

-- Exact upstream SHA-256 pins, including the unmodified upstream license.
sourceHashes :: [(FilePath, String)]
sourceHashes =
  [ ("GHC/Internal/Arr.hs", "d91e8d645309242c5a7b849d6d14884816cd4b04f4fc741e21f62d75a4d2bc4d")
  , ("GHC/Internal/Base.hs", "bc38ea9356f90aeb38298ef1269fbdc2dee433528374948375da112b273a89e2")
  , ("GHC/Internal/Bignum/Natural.hs", "6895337089fc3ab8a5102b0853c28a4b70281b49ba7705b00eb9750a7e13d8df")
  , ("GHC/Internal/ClosureTypes.hs", "38ca5c8e07a5b807efdce7ac283f42f281347f36195b447067c71dd3c59647ca")
  , ("GHC/Internal/CString.hs", "3b2e7a0fb2880d8f98cb002adfbaa36a8469667b7494f8f695fe1a6f181de573")
  , ("GHC/Internal/Control/Monad/Fail.hs", "695c8290891399db66075b86ecb1fd0a6242788985023d3edfb9c7155b97ecb7")
  , ("GHC/Internal/Data/Either.hs", "cbac81710a7e01a8a43616d8d88f9a387d6b0e1bbe5f4410dc1f172dc5f765e6")
  , ("GHC/Internal/Data/Maybe.hs", "c1e3833e2becd8d0ac4ee9ccb9e7615cb7f469c25cf9cfde500e21b46f586057")
  , ("GHC/Internal/Data/OldList.hs", "4b519356f7bcbd705866551e26552c84b08d527fe93b060cc89b27882aec6248")
  , ("GHC/Internal/Data/Typeable/Internal.hs", "ff1a002f75f349dbc4699273c3ce6e8e4dba9841aa5ad4aee20d3beb8d8b54e8")
  , ("GHC/Internal/Data/Tuple.hs", "97658f1c5b9be7910c23480641fb8f1aedbdbe96d0e2d68b5c6106aa48e7a7ad")
  , ("GHC/Internal/Enum.hs", "e4dcf86915b01dcc732ed319fe02759858aea1534c68427826ba5f9c6908860f")
  , ("GHC/Internal/Enum.hs-boot", "47353434d99287294958f62ae98303fa3cf6775dc416f95055bd41a2f95a8449")
  , ("GHC/Internal/Err.hs", "f109ac925928a0e7fed063bcfd03c93e3d8629d8054d984e600aab26c0122478")
  , ("GHC/Internal/ExecutionStack/Internal.hsc", "1ef77fe1313327ecb505c3d600e8324610bf31fc3de11266f767abe5da57442f")
  , ("GHC/Internal/Exception/Backtrace.hs", "2f062109b7a7b40db88bffaef4c9529123008403600bbf6c87b529305625fba8")
  , ("GHC/Internal/Exception/Backtrace.hs-boot", "7a57658044a20df0c72ea4ce2b15168b61542c94ed0a8befdf6e29792b549df7")
  , ("GHC/Internal/Exception/Context.hs", "49ac1971d2e8f4947ef3d5dd28fe9497d178f24ac367f2767542257799fb4dc6")
  , ("GHC/Internal/Exception/Type.hs", "70180e8a8a9a8c74e057a8ec1f4dce5f810eedffeff4ce8370cf33118d222d38")
  , ("GHC/Internal/Exception/Type.hs-boot", "f2a0440d35e33a8688d692cf50e4d33e74e92f08e84b04ab8aec20fd4861d2e0")
  , ("GHC/Internal/Exception.hs", "890128de0336c4762b44f709131a8959bf47bc0313b55bed4c071a17b62a9327")
  , ("GHC/Internal/Exception.hs-boot", "7422fa92308439db3c0ca034b02522c96e7961cff00754d0bdca964a4f98bc15")
  , ("GHC/Internal/Fingerprint.hs", "d97e24beb911ef802c3020690480eb4ac59ec0757ab9b482337c94a6963a7e5b")
  , ("GHC/Internal/Foreign/C/String/Encoding.hs", "a1956c04e77737b5796af679df63e884fdb8d0acc6bff40373dbbbd732837d57")
  , ("GHC/Internal/Foreign/Marshal/Alloc.hs", "76bbfcaf09561b667f49c595b9669f1e0808ac495b5754184bd6daa2a961458f")
  , ("GHC/Internal/Foreign/Storable.hs", "dda27f3c55cda6fbce4d44c127b6b26f5f18aa1e8b7ade6510e51cd4eec9e9cd")
  , ("GHC/Internal/ForeignPtr.hs", "8b7b040cd30b3e72c81957b616c13587e14caf9db9dec25e4a7580dac3ca3272")
  , ("GHC/Internal/Heap/Closures.hs", "d2e891979f6c561db40168bdb736a9f3ce67e915459f7ca301a57b1a547c0539")
  , ("GHC/Internal/Heap/Constants.hsc", "fe6012d406045f3808c8e2cb693b0bdc627a0bd524b44e0708769e59317a5b77")
  , ("GHC/Internal/Heap/InfoTable.hsc", "3936cce70289fa88996eed01ecef793faa4a5f750eb182d195b9e6dbd33ee5a0")
  , ("GHC/Internal/Heap/InfoTable/Types.hsc", "f1de0789ad600bd757fd27be9fc92bd9ef77e4520a17f4df2d759ef25b9ad8ef")
  , ("GHC/Internal/InfoProv/Types.hsc", "63f455d41df424cf2dc20713a34d43991a261fb012a3f7f56895233a9b594315")
  , ("GHC/Internal/Fingerprint.hs-boot", "72b19673ee571ca87ebc67470efc62b1ec75c4d4dfe579b4c33d7ac1a90bb246")
  , ("GHC/Internal/IO/Exception.hs", "39aded90d3cce7be4282ea6df2fa0cbf754942ab1aa7c740b5409021170b7ce6")
  , ("GHC/Internal/IO/Encoding.hs", "1b517e6f7c3cd2753c5dd9fe210873cfed7a545eaaf92605d8909d2643539b64")
  , ("GHC/Internal/IO/Encoding/UTF8.hs", "00db78df7e4a9a5404bd3dc9e379d59392ab698c907393a5fe61d74b9020194a")
  , ("GHC/Internal/IO/Exception.hs-boot", "bda7e1dd1ac680f0f1126b467207fd4682175ecf1e6871c7b2786b7a25506dcf")
  , ("GHC/Internal/IO/Handle/Types.hs", "4c719b6081b5e689219380974f72ed294b231521312f5b9a53bd5202019c9c35")
  , ("GHC/Internal/IO/Handle/Types.hs-boot", "8a319eb137cc03c8dbbb9d37b77703f3a962d894092cbd4d4e44c8381eaf3767")
  , ("GHC/Internal/IO/Unsafe.hs", "407dad2a8abda44be6e689f6ac45079c9f6cdf6147e847a4a0f9147ecfb8330f")
  , ("GHC/Internal/IO.hs", "e621ee438883f255d6a540ef761b9d462060f2ad3ed9557e118d55cf964ea25e")
  , ("GHC/Internal/IO.hs-boot", "a687801a14b3b423d45bca16ea03facd5fa0a428f049bcf04c2d3726e272c702")
  , ("GHC/Internal/Ix.hs", "485f592cb602e532a1a13a603aedb7f59b8d215123e8aa8982cd39320fed8c16")
  , ("GHC/Internal/List.hs", "ae9f56a758942b6e937e7b430ac1137e3ebea171762120ad9c31ef1f4904ba39")
  , ("GHC/Internal/Num.hs-boot", "b765e848138b1d4a22710c45db2e446d3e2c5c07c6b774a8d5cf83a5c4a9b92f")
  , ("GHC/Internal/Ptr.hs", "92ef7f10fc4f23ffa860a729c3b2fe13a269c1288dc3d6a017a358c894ec23c6")
  , ("GHC/Internal/Real.hs-boot", "843ed3133589748fbc65e0d7ef7e5a5491dc131b55ff73b67f6e3c3516bb99f4")
  , ("GHC/Internal/Show.hs", "b37f6d9d376e837785d207f2cf784daf0a53a04db26723a456c073616512be98")
  , ("GHC/Internal/Stack/Decode.hs", "0ea6a82ea41bdf14b28aec5cb36a586ed86eb6f87f373ea21095d2b1b018089f")
  , ("GHC/Internal/Stack/Annotation.hs", "96ad02226d0f5a9dd57b4ad873ceb2056e3e27ce788149f1e94ba29d0c83bb15")
  , ("GHC/Internal/Stack/CCS.hsc", "f146896b0038ad0a645e6b6dca39099d300157f77e443a0ac28545ae44fee5be")
  , ("GHC/Internal/Stack/CloneStack.hs", "1d3bd4ca3252beb53f308bb68abb2a6adfdb21d232849b63bc7009dbc77f4cea")
  , ("GHC/Internal/Stack/Constants.hsc", "7b02d3388f0a0c129bb73c10759a30733e641788ade399a8985f1063746fc4e6")
  , ("GHC/Internal/Stack/Types.hs", "8a035fa9a684c3c3e0ebcc7bc418992503fc322c7ef3d5d6a24023870b29fc25")
  , ("GHC/Internal/Stack.hs", "ab43f19c8fab1732afde9973838dcdfeb625a9af8565fcc493444574a3a8bb5c")
  , ("GHC/Internal/Stack.hs-boot", "7d92acf93446e191068370934f911fea3bfc26a0fc3a78b251f7b399c644677f")
  , ("GHC/Internal/Types.hs", "a8fe6ab5c7a84be9b0710078b549aa63e511e7cb12d056cef91403d05ca6d2bb")
  , ("GHC/Internal/Word.hs", "9ca67ff65c2cc2a0f28442e4fedd7685b7bfac387d2c24f5333477ba38221fad")
  , ("LICENSE", "768c070bd0b7d820d169ee8153d5487acfc262cbbc10dfce18d05c0bb2d2800d")
  , ("include/WordSize.h", "16e46daa3e38bfc98adb9360e54af211cada707d551a7720c00af4af907af090")
  ]

data WiredArtifacts = WiredArtifacts
  { generatedSources :: [(FilePath, FilePath)]
  , targetLayout :: FilePath
  }

exportPinnedCore :: FilePath -> FilePath -> FilePath -> FilePath -> String ->
                    FilePath -> FilePath -> IO WiredArtifacts
exportPinnedCore sourceRoot ghc ghcPkg pluginLibrary pluginUnit layoutRecipe staging = do
  let overlay = staging </> "interfaces"
      core = staging </> "core"
  createDirectoryIfMissing True overlay
  createDirectoryIfMissing True core
  installedText <- readProcess ghcPkg
    ["field", "ghc-internal", "import-dirs", "--simple-output"] ""
  installed <- case lines installedText of
    [path] | not (null path) -> pure path
    _ -> fail "ghc-internal must have one installed interface directory"
  linkInterfaces installed overlay installed
  includeDirs <- concatMap words <$> mapM (\package ->
    readProcess ghcPkg ["field", package, "include-dirs", "--simple-output"] "")
    ["rts", "ghc-internal"]
  when (null includeDirs) (fail "GHC target include directories are unavailable")
  let sibling = takeDirectory ghc </> "hsc2hs"
  siblingExists <- doesFileExist sibling
  hsc2hs <- if siblingExists then pure sibling else
    findExecutable "hsc2hs" >>= maybe (fail "hsc2hs is unavailable") pure
  generated <- forM [path | (path, _) <- moduleSources, takeExtension path == ".hsc"] $ \path -> do
    let output = staging </> "generated" </> replaceExtension path "hs"
        flags = [path, "-o", output] ++ map ("--cflag=-I" ++) includeDirs
    createDirectoryIfMissing True (takeDirectory output)
    (_, _, _, process) <- createProcess (proc hsc2hs flags) { cwd = Just sourceRoot }
    status <- waitForProcess process
    when (status /= ExitSuccess) (fail ("GHC source preprocessing failed: " ++ path))
    pure (path, output)
  compiler <- findExecutable "cc" >>= maybe (fail "C compiler is unavailable") pure
  let layoutBinary = staging </> "target-layout"
      layoutJson = staging </> "target-layout.json"
  layoutStatus <- rawSystem compiler
    (["-Wall", "-Werror"] ++ map ("-I" ++) includeDirs ++
     [layoutRecipe, "-o", layoutBinary])
  when (layoutStatus /= ExitSuccess) (fail "GHC target layout receipt compilation failed")
  writeFile layoutJson =<< readProcess layoutBinary [] ""
  let common = ["-c", "-dynamic", "-fforce-recomp", "-XNoPolyKinds",
                "-this-unit-id", "ghc-internal", "-package", "ghc-internal",
                "-odir", overlay, "-hidir", overlay, "-I" ++ (sourceRoot </> "include")]
      plugin = "-fplugin-library=" ++ pluginLibrary ++ ";" ++ pluginUnit ++
               ";THC.Plugin;" ++ BL.unpack (encode [core, "post-tidy", "source-notes"])
      compile boot path = do
        let target = overlay </> replaceExtension path (if boot then "hi-boot" else "hi")
        exists <- doesFileExist target
        when exists (removeFile target)
        let flags = if boot then [] else
              ["-O2", "-dcore-lint", "-g", plugin] ++
              ["-XNoImplicitPrelude" | path `elem`
                ["GHC/Internal/Stack/Decode.hs", "GHC/Internal/ExecutionStack/Internal.hsc"]]
            source = maybe (sourceRoot </> path) id (lookup path generated)
        status <- rawSystem ghc (common ++ flags ++ [source])
        when (status /= ExitSuccess) $ fail ("pinned GHC source export failed: " ++ path)
  forM_ bootSources (compile True)
  forM_ moduleSources (compile False . fst)
  pure (WiredArtifacts generated layoutJson)

linkInterfaces :: FilePath -> FilePath -> FilePath -> IO ()
linkInterfaces installed overlay directory = do
  names <- listDirectory directory
  forM_ names $ \name -> do
    let source = directory </> name
    isDirectory <- doesDirectoryExist source
    if isDirectory then linkInterfaces installed overlay source
    else when (".dyn_hi" `isSuffixOf` name) $ do
      let target = overlay </> replaceExtension (makeRelative installed source) "hi"
      createDirectoryIfMissing True (takeDirectory target)
      createFileLink source target
