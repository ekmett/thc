{-# LANGUAGE GADTs #-}
{-# LANGUAGE NamedFieldPuns #-}
-- | GHC 9.14.1 interactive compilation, stopping before user evaluation.
--
-- This is an adapter to GHCi's compiler/session machinery, not a command loop.
-- The caller owns a persistent guest-value table; context commits below update
-- only GHC's type/name environment, after that caller has installed the values.
module THC.Interactive
  ( PreparedStatement(..), PreparedDeclarations(..)
  , prepareStatement, prepareParsedStatement, prepareDeclarations, prepareParsedDeclarations
  , commitStatementContext, commitDeclarationsContext
  , statementJSON, desugaredStatementJSON, declarationsJSON, installModuleCapture, rejectNativeEvaluation
  ) where

import GHC hiding (exprType)
import GHC.Plugins hiding (getSession, setSession, getHscEnv)
import GHC.Core.Tidy (tidyExpr)
import GHC.Core.Opt.Pipeline (simplifyExpr)
import GHC.Driver.Config.Core.Lint.Interactive (lintInteractiveExpr)
import GHC.Driver.Config.Core.Opt.Simplify (initSimplifyExprOpts)
import GHC.Builtin.Names (mkInteractiveModule)
import GHC.Unit.Env (ue_eps)
import GHC.Core.ConLike (ConLike(..))
import GHC.Driver.Env (runInteractiveHsc, mkInteractiveHscEnv)
import GHC.Driver.Errors.Types (hoistTcRnMessage, hoistDsMessage)
import GHC.Driver.Hooks
import GHC.Driver.Main
import GHC.Driver.Pipeline.Execute (runPhase)
import GHC.Driver.Pipeline.Phases
import GHC.HsToCore (deSugarExpr)
import GHC.Runtime.Context
import GHC.Tc.Module (tcRnStmt, tcRnDeclsi)
import GHC.Tc.Utils.Monad (tcg_default, tcg_th_coreplugins, tcg_fix_env)
import GHC.Types.Fixity.Env (FixityEnv)
import GHC.Types.Unique.Supply (uniqFromTag)
import GHC.Unit.Module.ModDetails (ModDetails(..))
import GHC.Unit.Module.Location (ModLocation(..))
import Data.IORef (readIORef)
import Control.Monad (unless)
import Control.Monad.IO.Class (liftIO)
import THC.Plugin (InteractiveStage(..), serializeInteractiveCore)

data PreparedStatement = PreparedStatement
  { statementBindings :: [Id]
  , statementDesugaredCore :: CoreExpr
  , statementCore :: CoreExpr
  , statementRoot :: Id
  , statementModule :: Module
  , statementFixities :: FixityEnv
  , statementPreviousIndex :: Int
  }

data PreparedDeclarations = PreparedDeclarations
  { declarationGuts :: CgGuts
  , declarationThings :: [TyThing]
  , declarationContext :: InteractiveContext
  , declarationPreviousIndex :: Int
  }

prepareStatement :: GhcMonad m => FilePath -> Int -> String -> m (Maybe PreparedStatement)
prepareStatement source line input = do
  env <- getSession
  parsed <- liftIO $ runInteractiveHsc env $ hscParseStmtWithLocation source line input
  traverse prepareParsedStatement parsed

-- The frontend sequence is the one in GHC.Driver.Main.hscParsedStmt. In
-- particular tcRnStmt supplies GHCi's own IO [Any] wrapper and externalised Ids.
prepareParsedStatement :: GhcMonad m => GhciLStmt GhcPs -> m PreparedStatement
prepareParsedStatement stmt = do
  env <- getSession
  liftIO $ runInteractiveHsc env $ do
    current <- getHscEnv
    (ids, typed, fixities) <- ioMsgMaybe $ hoistTcRnMessage $ tcRnStmt current stmt
    core <- ioMsgMaybe $ hoistDsMessage $ deSugarExpr current typed
    liftIO $ lintInteractiveExpr (text "THC desugar expression") current core
    simplified <- liftIO $ simplifyExpr (hsc_logger current) (ue_eps (hsc_unit_env current))
                    (initSimplifyExprOpts (hsc_dflags current) (hsc_IC current)) core
    unique <- liftIO $ uniqFromTag 'T'
    -- Several prepared statements can share an interactive name epoch. Give
    -- each executable root a fresh module, like hscCompileCoreExpr' itself.
    let m = mkInteractiveModule ("THC" ++ show unique)
        name = mkExternalName unique m (mkVarOcc "thc_statement") noSrcSpan
        root = mkExportedVanillaId name (exprType simplified)
        tidied = tidyExpr (mkEmptyTidyEnv (initTidyOccEnv [occName root])) simplified
    liftIO $ lintInteractiveExpr (text "THC tidied expression") current tidied
    pure PreparedStatement
      { statementBindings = ids, statementDesugaredCore = core, statementCore = tidied, statementRoot = root
      , statementModule = m, statementFixities = fixities
      , statementPreviousIndex = ic_mod_index (hsc_IC current) }

prepareDeclarations :: GhcMonad m => FilePath -> Int -> String -> m PreparedDeclarations
prepareDeclarations source line input = do
  env <- getSession
  parsed <- liftIO $ hscParseDeclsWithLocation env source line input
  prepareParsedDeclarations parsed

-- GHC.Driver.Main.hscParsedDecls, through Tidy and context construction. The
-- bytecode generation, loadDecls and static-pointer execution tail is omitted.
prepareParsedDeclarations :: GhcMonad m => [LHsDecl GhcPs] -> m PreparedDeclarations
prepareParsedDeclarations decls = do
  env <- getSession
  liftIO $ runInteractiveHsc env $ do
    current <- getHscEnv
    checked <- ioMsgMaybe $ hoistTcRnMessage $ tcRnDeclsi current decls
    let location = OsPathModLocation
          { ml_hs_file_ospath = Nothing
          , ml_hi_file_ospath = error "interactive capture has no interface output"
          , ml_obj_file_ospath = error "interactive capture has no object output"
          , ml_dyn_hi_file_ospath = error "interactive capture has no dynamic interface output"
          , ml_dyn_obj_file_ospath = error "interactive capture has no dynamic object output"
          , ml_hie_file_ospath = error "interactive capture has no HIE output" }
    desugared <- hscDesugar' location checked
    plugins <- liftIO $ readIORef (tcg_th_coreplugins checked)
    simplified <- liftIO $ hscSimplify current plugins desugared
    (guts, details) <- liftIO $ hscTidy current simplified
    let types = filter (not . isImplicitTyCon) (mg_tcs simplified)
        patterns = mg_patsyns simplified
        ids = [v | v <- bindersOfBinds (cg_binds guts), isExternalName (idName v)
                 , not (isDFunId v || isImplicitId v)]
        things = map AnId ids ++ map ATyCon types ++ map (AConLike . PatSynCon) patterns
        prior = hsc_IC current
        context = extendInteractiveContext prior things (md_insts details) (md_fam_insts details)
                    (tcg_default checked) (tcg_fix_env checked)
    pure PreparedDeclarations
      { declarationGuts = guts, declarationThings = things, declarationContext = context
      , declarationPreviousIndex = ic_mod_index prior }

-- These are type-context commits, not evaluation. The backend must first
-- install the returned binding Id/value pairs or complete declaration group.
-- A stale prepared result cannot overwrite a newer interactive name epoch.
commitStatementContext :: GhcMonad m => PreparedStatement -> m ()
commitStatementContext prepared = do
  env <- getSession
  checkEpoch (statementPreviousIndex prepared) (hsc_IC env)
  let context = extendInteractiveContextWithIds (hsc_IC env) (statementBindings prepared)
  setSession env { hsc_IC = context { ic_fix_env = statementFixities prepared } }

commitDeclarationsContext :: GhcMonad m => PreparedDeclarations -> m ()
commitDeclarationsContext prepared = do
  env <- getSession
  checkEpoch (declarationPreviousIndex prepared) (hsc_IC env)
  setSession env { hsc_IC = declarationContext prepared }

checkEpoch :: GhcMonad m => Int -> InteractiveContext -> m ()
checkEpoch expected actual = unless (expected == ic_mod_index actual) $
  liftIO $ ioError $ userError "THC interactive capture belongs to a stale name epoch"

statementJSON :: HscEnv -> PreparedStatement -> IO String
statementJSON env p = serializeInteractiveCore (mkInteractiveHscEnv env) (statementModule p)
  InteractiveStatement [NonRec (statementRoot p) (statementCore p)] [] (statementBindings p)

desugaredStatementJSON :: HscEnv -> PreparedStatement -> IO String
desugaredStatementJSON env p = serializeInteractiveCore (mkInteractiveHscEnv env) (statementModule p)
  InteractiveDesugaredStatement [NonRec (statementRoot p) (statementDesugaredCore p)] [] (statementBindings p)

declarationsJSON :: HscEnv -> PreparedDeclarations -> IO String
declarationsJSON env p = let guts = declarationGuts p in
  serializeInteractiveCore (mkInteractiveHscEnv env) (cg_module guts)
    InteractiveDeclarations (cg_binds guts) (cg_tycons guts) []

-- Wrap, do not replace, an existing pipeline hook. Module Core is observed
-- after ordinary GHC simplification/Tidy and before the selected backend.
installModuleCapture :: (HscEnv -> CgGuts -> IO ()) -> HscEnv -> HscEnv
installModuleCapture capture env = env { hsc_hooks = hooks { runPhaseHook = Just (PhaseHook phase) } }
  where
    hooks = hsc_hooks env
    previous :: TPhase a -> IO a
    previous = case runPhaseHook hooks of Nothing -> runPhase; Just (PhaseHook f) -> f
    phase :: TPhase a -> IO a
    phase p@(T_HscPostTc current _ _ _ _) = do
      result <- previous p
      case result of HscRecomp { hscs_guts } -> capture current hscs_guts; _ -> pure ()
      pure result
    phase p = previous p

-- A capture-only session must not silently execute native user statements or
-- Template Haskell. This guard deliberately has no fallback implementation.
rejectNativeEvaluation :: HscEnv -> HscEnv
rejectNativeEvaluation env = env { hsc_hooks = (hsc_hooks env)
  { hscCompileCoreExprHook = Just (\_ _ _ -> denied)
  , runMetaHook = Just (\_ _ -> liftIO denied) } }
  where denied = ioError $ userError "Native user evaluation is disabled in THC capture sessions"
