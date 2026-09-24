-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

module THC.Driver.Wired
  ( bootSources, moduleSources, sourceHashes, exportPinnedCore ) where

import Control.Monad (forM_, when)
import qualified Data.ByteString.Lazy.Char8 as BL
import Data.Aeson (encode)
import Data.List (isSuffixOf)
import System.Directory (createDirectoryIfMissing, createFileLink, doesDirectoryExist, doesFileExist,
                         listDirectory, removeFile)
import System.Exit (ExitCode(..))
import System.FilePath ((</>), makeRelative, replaceExtension, takeDirectory)
import System.Process (readProcess, rawSystem)

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
  , ("GHC/Internal/Bignum/Natural.hs", "GHC.Internal.Bignum.Natural")
  , ("GHC/Internal/Base.hs", "GHC.Internal.Base")
  , ("GHC/Internal/Show.hs", "GHC.Internal.Show")
  , ("GHC/Internal/Err.hs", "GHC.Internal.Err")
  , ("GHC/Internal/Exception/Type.hs", "GHC.Internal.Exception.Type")
  , ("GHC/Internal/IO/Exception.hs", "GHC.Internal.IO.Exception")
  , ("GHC/Internal/IO.hs", "GHC.Internal.IO")
  , ("GHC/Internal/Control/Monad/Fail.hs", "GHC.Internal.Control.Monad.Fail")
  , ("GHC/Internal/Exception/Context.hs", "GHC.Internal.Exception.Context")
  , ("GHC/Internal/Exception.hs", "GHC.Internal.Exception")
  , ("GHC/Internal/Stack.hs", "GHC.Internal.Stack")
  , ("GHC/Internal/IO/Handle/Types.hs", "GHC.Internal.IO.Handle.Types")
  , ("GHC/Internal/Exception/Backtrace.hs", "GHC.Internal.Exception.Backtrace")
  , ("GHC/Internal/Data/Maybe.hs", "GHC.Internal.Data.Maybe")
  , ("GHC/Internal/Data/OldList.hs", "GHC.Internal.Data.OldList")
  , ("GHC/Internal/Stack/Types.hs", "GHC.Internal.Stack.Types")
  , ("GHC/Internal/Stack/Decode.hs", "GHC.Internal.Stack.Decode")
  , ("GHC/Internal/CString.hs", "GHC.Internal.CString")
  ]

-- Exact upstream SHA-256 pins, including the unmodified upstream license.
sourceHashes :: [(FilePath, String)]
sourceHashes =
  [ ("GHC/Internal/Arr.hs", "d91e8d645309242c5a7b849d6d14884816cd4b04f4fc741e21f62d75a4d2bc4d")
  , ("GHC/Internal/Base.hs", "bc38ea9356f90aeb38298ef1269fbdc2dee433528374948375da112b273a89e2")
  , ("GHC/Internal/Bignum/Natural.hs", "6895337089fc3ab8a5102b0853c28a4b70281b49ba7705b00eb9750a7e13d8df")
  , ("GHC/Internal/CString.hs", "3b2e7a0fb2880d8f98cb002adfbaa36a8469667b7494f8f695fe1a6f181de573")
  , ("GHC/Internal/Control/Monad/Fail.hs", "695c8290891399db66075b86ecb1fd0a6242788985023d3edfb9c7155b97ecb7")
  , ("GHC/Internal/Data/Maybe.hs", "c1e3833e2becd8d0ac4ee9ccb9e7615cb7f469c25cf9cfde500e21b46f586057")
  , ("GHC/Internal/Data/OldList.hs", "4b519356f7bcbd705866551e26552c84b08d527fe93b060cc89b27882aec6248")
  , ("GHC/Internal/Data/Typeable/Internal.hs", "ff1a002f75f349dbc4699273c3ce6e8e4dba9841aa5ad4aee20d3beb8d8b54e8")
  , ("GHC/Internal/Enum.hs-boot", "47353434d99287294958f62ae98303fa3cf6775dc416f95055bd41a2f95a8449")
  , ("GHC/Internal/Err.hs", "f109ac925928a0e7fed063bcfd03c93e3d8629d8054d984e600aab26c0122478")
  , ("GHC/Internal/Exception/Backtrace.hs", "2f062109b7a7b40db88bffaef4c9529123008403600bbf6c87b529305625fba8")
  , ("GHC/Internal/Exception/Backtrace.hs-boot", "7a57658044a20df0c72ea4ce2b15168b61542c94ed0a8befdf6e29792b549df7")
  , ("GHC/Internal/Exception/Context.hs", "49ac1971d2e8f4947ef3d5dd28fe9497d178f24ac367f2767542257799fb4dc6")
  , ("GHC/Internal/Exception/Type.hs", "70180e8a8a9a8c74e057a8ec1f4dce5f810eedffeff4ce8370cf33118d222d38")
  , ("GHC/Internal/Exception/Type.hs-boot", "f2a0440d35e33a8688d692cf50e4d33e74e92f08e84b04ab8aec20fd4861d2e0")
  , ("GHC/Internal/Exception.hs", "890128de0336c4762b44f709131a8959bf47bc0313b55bed4c071a17b62a9327")
  , ("GHC/Internal/Exception.hs-boot", "7422fa92308439db3c0ca034b02522c96e7961cff00754d0bdca964a4f98bc15")
  , ("GHC/Internal/Fingerprint.hs", "d97e24beb911ef802c3020690480eb4ac59ec0757ab9b482337c94a6963a7e5b")
  , ("GHC/Internal/Fingerprint.hs-boot", "72b19673ee571ca87ebc67470efc62b1ec75c4d4dfe579b4c33d7ac1a90bb246")
  , ("GHC/Internal/IO/Exception.hs", "39aded90d3cce7be4282ea6df2fa0cbf754942ab1aa7c740b5409021170b7ce6")
  , ("GHC/Internal/IO/Exception.hs-boot", "bda7e1dd1ac680f0f1126b467207fd4682175ecf1e6871c7b2786b7a25506dcf")
  , ("GHC/Internal/IO/Handle/Types.hs", "4c719b6081b5e689219380974f72ed294b231521312f5b9a53bd5202019c9c35")
  , ("GHC/Internal/IO.hs", "e621ee438883f255d6a540ef761b9d462060f2ad3ed9557e118d55cf964ea25e")
  , ("GHC/Internal/IO.hs-boot", "a687801a14b3b423d45bca16ea03facd5fa0a428f049bcf04c2d3726e272c702")
  , ("GHC/Internal/Ix.hs", "485f592cb602e532a1a13a603aedb7f59b8d215123e8aa8982cd39320fed8c16")
  , ("GHC/Internal/List.hs", "ae9f56a758942b6e937e7b430ac1137e3ebea171762120ad9c31ef1f4904ba39")
  , ("GHC/Internal/Num.hs-boot", "b765e848138b1d4a22710c45db2e446d3e2c5c07c6b774a8d5cf83a5c4a9b92f")
  , ("GHC/Internal/Real.hs-boot", "843ed3133589748fbc65e0d7ef7e5a5491dc131b55ff73b67f6e3c3516bb99f4")
  , ("GHC/Internal/Show.hs", "b37f6d9d376e837785d207f2cf784daf0a53a04db26723a456c073616512be98")
  , ("GHC/Internal/Stack/Decode.hs", "0ea6a82ea41bdf14b28aec5cb36a586ed86eb6f87f373ea21095d2b1b018089f")
  , ("GHC/Internal/Stack/Types.hs", "8a035fa9a684c3c3e0ebcc7bc418992503fc322c7ef3d5d6a24023870b29fc25")
  , ("GHC/Internal/Stack.hs", "ab43f19c8fab1732afde9973838dcdfeb625a9af8565fcc493444574a3a8bb5c")
  , ("GHC/Internal/Stack.hs-boot", "7d92acf93446e191068370934f911fea3bfc26a0fc3a78b251f7b399c644677f")
  , ("GHC/Internal/Types.hs", "a8fe6ab5c7a84be9b0710078b549aa63e511e7cb12d056cef91403d05ca6d2bb")
  , ("LICENSE", "768c070bd0b7d820d169ee8153d5487acfc262cbbc10dfce18d05c0bb2d2800d")
  , ("include/WordSize.h", "16e46daa3e38bfc98adb9360e54af211cada707d551a7720c00af4af907af090")
  ]

exportPinnedCore :: FilePath -> FilePath -> FilePath -> FilePath -> String ->
                    FilePath -> IO ()
exportPinnedCore sourceRoot ghc ghcPkg pluginLibrary pluginUnit staging = do
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
              ["-XNoImplicitPrelude" | path == "GHC/Internal/Stack/Decode.hs"]
        status <- rawSystem ghc (common ++ flags ++ [sourceRoot </> path])
        when (status /= ExitSuccess) $ fail ("pinned GHC source export failed: " ++ path)
  forM_ bootSources (compile True)
  forM_ moduleSources (compile False . fst)

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
