module Main where
import GHC hiding (exprType)
import GHC.Core.ConLike (ConLike(..))
import GHC.Plugins
import GHC.Builtin.Names (unsafeEqualityProofName, unsafeReflDataConName)
import GHC.Builtin.Types (intTy, liftedTypeKind, trueDataCon)
import GHC.Types.Basic (OccInfo(..))
import Data.Maybe (isJust)
import System.Environment (getArgs)
import Thc.Wired (wiredCase)

main :: IO ()
main = do
  [libdir] <- getArgs
  runGhc (Just libdir) $ do
    flags <- getSessionDynFlags
    _ <- setSessionDynFlags flags
    setContext [IIDecl (simpleImportDecl (mkModuleName "Unsafe.Coerce"))]
    _ <- parseName "Unsafe.Coerce.unsafeEqualityProof"
    Just (AnId proof) <- lookupName unsafeEqualityProofName
    Just (AConLike (RealDataCon refl)) <- lookupName unsafeReflDataConName
    let scrut = mkApps (Var proof) [Type liftedTypeKind, Type intTy, Type intTy]
        binder = mkTemplateLocal 101 (exprType scrut)
        dead = setIdOccInfo binder IAmDead
        rhs = Var (mkTemplateLocal 102 intTy)
        alt = Alt (DataAlt refl) [] rhs
        test s b as = Case s b intTy as
        match e = isJust (wiredCase e)
        wrong = setIdUnique proof (getUnique (mkTemplateLocal 103 (idType proof)))
        checks =
          [ ("actual wired case", True, test scrut dead [alt])
          , ("live case binder", False, Case scrut binder (exprType scrut) [Alt (DataAlt refl) [] (Var binder)])
          , ("same spelling, different wired key", False, test (mkApps (Var wrong) [Type liftedTypeKind, Type intTy, Type intTy]) dead [alt])
          , ("wrong alternative", False, test scrut dead [Alt (DataAlt trueDataCon) [] rhs])
          , ("default alternative", False, test scrut dead [Alt DEFAULT [] rhs])
          , ("two alternatives", False, test scrut dead [alt,alt])
          , ("no alternatives", False, test scrut dead [])
          , ("bare first-class proof", False, Var proof)
          , ("erased arguments", False, test (Var proof) dead [alt])
          , ("too few arguments", False, test (mkApps (Var proof) [Type liftedTypeKind, Type intTy]) dead [alt])
          , ("too many arguments", False, test (App scrut (Type intTy)) dead [alt])
          ]
    liftIO $ mapM_ (\(label, expected, value) ->
      if match value == expected then putStrLn ("PASS " ++ label) else error label) checks
