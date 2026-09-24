{-# LANGUAGE LambdaCase #-}
module THC.Sources
  ( Note(..), SourceFile(..), SourceSpan(..), SourceTable(..)
  , tickNote, binderNote, noteKey, buildSourceTable, hasNote
  ) where

import GHC.Plugins
import GHC.Types.Tickish (CoreTickish, GenTickish(SourceNote))
import Control.Exception (IOException, evaluate, try)
import Data.Char (ord)
import qualified Data.Map.Strict as Map
import qualified Data.Set as Set
import Data.Maybe (mapMaybe)
import System.IO (IOMode(ReadMode), hGetContents, hSetEncoding, utf8, withFile)

data Note = Note { noteSpan :: RealSrcSpan, noteLabel :: String }

data SourceFile = SourceFile
  { sourceFileId :: String, sourceFilePath :: String, sourceFileContent :: Maybe String }

data SourceSpan = SourceSpan
  { sourceSpanId :: String, sourceSpanFile :: String, sourceSpanNote :: Note
  , sourceCharIndex :: Maybe Int, sourceCharLength :: Maybe Int }

data SourceTable = SourceTable
  { sourceFiles :: [SourceFile], sourceSpans :: [SourceSpan], sourceSpanKeys :: Set.Set String }

tickNote :: CoreTickish -> Maybe Note
tickNote (SourceNote span label) = Just (Note span (unpackFS (getLexicalFastString label)))
tickNote _ = Nothing

binderNote :: Var -> Maybe Note
binderNote v = case nameSrcSpan (varName v) of
  RealSrcSpan span _ -> Just (Note span (occNameString (nameOccName (varName v))))
  _ -> Nothing

-- Opaque, deterministic keys; consumers never parse them. Encoding a tuple
-- avoids collisions involving path separators, colons or unusual source names.
noteKey :: Note -> String
noteKey (Note span label) = show
  (unpackFS (srcSpanFile span), srcSpanStartLine span, srcSpanStartCol span,
   srcSpanEndLine span, srcSpanEndCol span, label)

hasNote :: SourceTable -> Note -> Bool
hasNote table note = noteKey note `Set.member` sourceSpanKeys table

buildSourceTable :: [(Id, CoreExpr)] -> IO SourceTable
buildSourceTable bindings = do
  let notes = Map.fromList [(noteKey note,note) | note <- concatMap bindingNotes bindings]
      files = Map.fromListWith (++) [(unpackFS (srcSpanFile (noteSpan note)),[note]) | note <- Map.elems notes]
  pairs <- mapM readSource (Map.toAscList files)
  pure (SourceTable (map fst pairs) (concatMap snd pairs) (Map.keysSet notes))
  where
    bindingNotes (v,e) = maybe [] (:[]) (binderNote v) ++ expressionNotes e
    bindNotes (NonRec v e) = bindingNotes (v,e)
    bindNotes (Rec pairs) = concatMap bindingNotes pairs
    expressionNotes = \case
      App f x -> expressionNotes f ++ expressionNotes x
      Lam v e -> maybe [] (:[]) (binderNote v) ++ expressionNotes e
      Let b e -> bindNotes b ++ expressionNotes e
      Case e v _ alts -> expressionNotes e ++ maybe [] (:[]) (binderNote v) ++
        concat [mapMaybe binderNote bs ++ expressionNotes rhs | Alt _ bs rhs <- alts]
      Cast e _ -> expressionNotes e
      Tick tick e -> maybe [] (:[]) (tickNote tick) ++ expressionNotes e
      _ -> []
    readSource (path,selected) = do
      text <- try (withFile path ReadMode $ \handle -> do
        hSetEncoding handle utf8
        contents <- hGetContents handle
        _ <- evaluate (length contents)
        pure contents) :: IO (Either IOException String)
      let content = either (const Nothing) Just text
          positions = concat [[(srcSpanStartLine s,srcSpanStartCol s),
                               (srcSpanEndLine s,srcSpanEndCol s)] | Note s _ <- selected]
          offsets = Map.fromDistinctAscList (maybe [] (`sourceOffsets` positions) content)
          source = SourceFile path path content
          spanRecord note@(Note span _) =
            let start = Map.lookup (srcSpanStartLine span,srcSpanStartCol span) offsets
                end = Map.lookup (srcSpanEndLine span,srcSpanEndCol span) offsets
                range = case (start,end) of
                  (Just from,Just to) | to >= from -> (Just from,Just (to-from))
                  _ -> (Nothing,Nothing)
            in SourceSpan (noteKey note) path note (fst range) (snd range)
      pure (source,map spanRecord selected)

-- GHC counts Unicode code points and expands tabs to eight-column stops;
-- Truffle indexes UTF-16 code units. Match only actual source boundaries, so
-- unavailable/out-of-range spans do not acquire guessed character offsets.
sourceOffsets :: String -> [(Int,Int)] -> [((Int,Int),Int)]
sourceOffsets contents wanted = scan 1 1 0 contents (Set.toAscList (Set.fromList wanted))
  where
    scan _ _ _ _ [] = []
    scan line column offset input targets@(target:rest)
      | target < (line,column) = scan line column offset input rest
      | target == (line,column) = (target,offset) : scan line column offset input rest
      | otherwise = case input of
          [] -> []
          c:cs -> let (line',column') = case c of
                       '\n' -> (line+1,1)
                       '\t' -> (line,((column-1) `div` 8 + 1)*8 + 1)
                       _ -> (line,column+1)
                      width = if ord c > 0xffff then 2 else 1
                  in scan line' column' (offset+width) cs targets
