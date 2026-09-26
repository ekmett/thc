-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}
module NativeRecipeTests (tests) where

import Control.Exception (bracket)
import Data.Aeson (Value, object, (.=))
import Data.Either (isLeft)
import Data.IORef
import GHC.ResponseFile (escapeArgs, unescapeArgs)
import System.Directory
import System.FilePath
import System.IO (hClose, openTempFile)
import System.IO.Error (tryIOError)
import Test.HUnit
import THC.Driver.NativeRecipe
import THC.Driver.ScalarBitcode (withScalarBitcode)

tests :: Test
tests = TestLabel "actual native compiler receipts" $ TestList
  [ TestCase $ do
      let args = ["-c", "cbits/a café.c", "-I/a \"quoted\" path", "-DVALUE=\\x"]
      assertEqual "GHC response quoting preserves complete arguments" args (unescapeArgs (escapeArgs args))
  , TestCase $ do
      let args = ["-package-env=-","-c","-fPIC","-odir","/owned build","-pgmc","/usr/bin/gcc",
                  "cbits/a.c","-O2","-optc-O2","-pgmc","/selected/clang","-fforce-recomp"]
      assertEqual "last explicit compiler wins and repair flag is harmless"
        (Right ("cbits/a.c","/selected/clang",["-O2"])) (cRecipeOptions args)
      mapM_ (assertBool "unsupported native recipes are closed" . isLeft . cRecipeOptions)
        [args ++ ["extra.c"], args ++ ["x.cpp"], args ++ ["-optc-fsanitize=address"],
         args ++ ["-pgma","/other/assembler"], args ++ ["-dynamic"], args ++ ["-prof"]]
  , TestCase $ withScratch $ \root -> withCurrentDirectory root $ do
      let native = root </> "native"
          dist = native </> "build"
          output = dist </> "cbits/proof.o"
          receipts = native </> "cache/thc/native-recipes-v1"
          component = metadata root dist
      createDirectoryIfMissing True (dist </> "cbits")
      createDirectory (root </> "cbits")
      writeFile "cbits/proof.c" "long long proof(long long x) { return x; }\n"
      writePackage root True
      compiler <- canonicalizePath "/usr/bin/true"
      calls <- newIORef (0 :: Int)
      let build = do
            modifyIORef' calls (+1)
            writeFile output "native-object"
            captureNativeRecipe receipts compiler ["-c","cbits/proof.c","-odir",dist]
          ensure = ensureNativeRecipes native dist [dist] compiler component build
      ensure
      assertEqual "absent object and receipt force acquisition" 1 =<< readIORef calls
      ensure
      assertEqual "valid warm recipe does not rebuild" 1 =<< readIORef calls
      removeFile (recipePath receipts output)
      ensure
      assertEqual "missing receipt forces observed rebuild" 2 =<< readIORef calls
      writeFile output "changed-object"
      ensure
      assertEqual "stale object hash forces observed rebuild" 3 =<< readIORef calls
      writeFile (recipePath receipts output) "not-json"
      ensure
      assertEqual "corrupt receipt forces observed rebuild" 4 =<< readIORef calls
      removeFile output
      removeFile (recipePath receipts output)
      failed <- tryIOError (ensureNativeRecipes native dist [dist] compiler component (pure ()))
      assertBool "successful command without object is rejected" (isLeft failed)
      ensure
      let child = dist </> "exe/exe-tmp"
      createDirectoryIfMissing True child
      writeFile (child </> "foreign.o") "other-component"
      assertEqual "nested component owns its objects" [output] =<<
        componentNativeObjects native dist [dist,child] component
      writeFile "Proof.c" "int proof;"
      writeFile (dist </> "Proof.o") "collision"
      writeFile (dist </> "Proof.hi") "interface"
      captureNativeRecipe receipts compiler ["-c","Proof.c","-odir",dist]
      collision <- tryIOError (componentNativeObjects native dist [dist,child] component)
      assertBool "a neighboring interface cannot conceal a C receipt" (isLeft collision)
      removeFile (recipePath receipts (dist </> "Proof.o"))
      removeFile output
      removeFile (recipePath receipts output)
      writeFile (root </> "proof.cabal") $ unlines
        ["cabal-version: 3.0","name: proof","version: 0.1","build-type: Simple","library",
         "  exposed-modules: Proof","  c-sources: Proof.c","  build-depends: base"]
      hidden <- tryIOError $ withScalarBitcode native dist [dist,child] compiler "/unused" "proof-0.1-inplace" component
        (const (pure ()))
      assertBool "public declaration guard rejects hidden C after receipt loss" (isLeft hidden)
  , TestCase $ withScratch $ \root -> do
      let native = root </> "native"; dist = native </> "build"; component = metadata root dist
      createDirectoryIfMissing True dist
      writePackage root False
      result <- withScalarBitcode native dist [dist] "/no-compiler-needed" "/no-package-tool-needed"
        "proof-0.1-inplace" component (pure . maybe True (const False))
      assertBool "no-C component needs neither setup-config nor native tools" result
  ]

metadata :: FilePath -> FilePath -> Value
metadata root dist = object ["type" .= ("lib" :: String), "name" .= ("lib" :: String),
  "modules" .= (["Proof"] :: [String]), "src-dir" .= root, "cabal-file" .= ("proof.cabal" :: String),
  "compiler-args" .= ["-odir", dist, "-hidir", dist]]

writePackage :: FilePath -> Bool -> IO ()
writePackage root native = writeFile (root </> "proof.cabal") $ unlines
  (["cabal-version: 3.0","name: proof","version: 0.1","build-type: Simple","library",
    "  exposed-modules: Proof","  build-depends: base","  default-language: Haskell2010"] ++
   ["  c-sources: cbits/proof.c" | native])

withScratch :: (FilePath -> IO a) -> IO a
withScratch = bracket create removePathForcibly
  where create = do
          temporary <- getTemporaryDirectory
          (path,handle) <- openTempFile temporary "native recipe café-"
          hClose handle
          removeFile path
          createDirectory path
          canonicalizePath path
