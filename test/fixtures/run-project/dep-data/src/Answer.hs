-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE CPP, MagicHash #-}
module Answer (answerValue) where
import GHC.Exts (Int(I#))
answerValue :: Int
#ifdef PROJECT_RECENT
answerValue = I# 42#
#else
answerValue = I# 41#
#endif
