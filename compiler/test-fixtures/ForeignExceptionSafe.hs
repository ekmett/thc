-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE Safe #-}
module ForeignExceptionSafe where
import THC.Exception
safeClient :: ForeignException -> IO (Maybe String)
safeClient = foreignExceptionMessage
