-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- | Check the certificate guard against genuine GHC class IDs and templates.
module Main (main) where

import Control.Monad (forM_, unless)
import GHC
import GHC.Plugins
import GHC.Core.Class (classAllSelIds, classTyCon)
import GHC.Core.Utils (isUnaryClassId)
import GHC.Types.TypeEnv (typeEnvTyCons)
import System.Environment (getArgs)
import THC.Wired (preservesWiredTypes, wiredRhs)

main :: IO ()
main = do
  [libdir, source] <- getArgs
  runGhc (Just libdir) $ do
    flags <- getSessionDynFlags
    _ <- setSessionDynFlags flags
    core <- compileToCoreSimplified source
    let classes = [cls | tc <- typeEnvTyCons (cm_types core), Just cls <- [tyConClass_maybe tc]]
        selectors = concatMap classAllSelIds classes
        unaryConstructors = [dataConWorkId dc | cls <- classes, dc <- tyConDataCons (classTyCon cls),
                                              isUnaryClassId (dataConWorkId dc)]
    liftIO $ do
      unless (length selectors == 5 && length unaryConstructors == 1)
        (error "Expected genuine Parent/Child/Unary declarations")
      forM_ (selectors ++ unaryConstructors) $ \v -> case wiredRhs v of
        Nothing -> error "Missing original selector/constructor template"
        Just lowered -> do
          let positive = not (isUnaryClassId v)
          unless (preservesWiredTypes (Var v) lowered == positive)
            (error ("Template certificate: " ++ getOccString v))
          -- Even equal types do not authorize unary erasure or an unrelated Id;
          -- a non-unary selector still requires equality with its actual type.
          unless (not (preservesWiredTypes (Var v) (Lit (LitChar 'x'))))
            (error "Different type accepted")
          unless (positive || not (preservesWiredTypes (Var v) (Var v)))
            (error "Unary equal-type shortcut accepted")
          putStrLn ("PASS exact selector guard " ++ getOccString v)
      ordinary <- case [v | (v, _) <- flattenBinds (cm_binds core), getOccString v == "method"] of
        [v] -> pure v
        _ -> error "Missing genuine ordinary function"
      unless (not (preservesWiredTypes (Var ordinary) (Var ordinary)))
        (error "Unrelated equal-type Id accepted")
      putStrLn "PASS unrelated equal-type exclusion"
