-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE OverloadedStrings #-}

-- Native fixture production belongs to Haskell. The JVM tests own the
-- independent arithmetic models and compiled guest comparisons.
module Main (main) where

import AggregateFixtures (prepareAggregate)
import WordFloatingFixtures (prepareWordFloating)
import FloatingAddressFixtures (prepareFloatingAddress)
import FloatingByteOffsetFixtures (prepareFloatingByteOffset)
import NarrowByteOffsetFixtures (prepareNarrowByteOffset)
import Int32ByteOffsetFixtures (prepareInt32ByteOffset)
import Explicit64ArrayFixtures (prepareExplicit64Array)
import FusedFloatingFixtures (prepareFusedFloating)
import SqrtFixtures (prepareSqrt)
import ContinuationFixtures (prepareCoreContinuation)
import LiveAsyncFixtures (prepareLiveAsync)
import ThreadAsyncFixtures (prepareThreadAsync)
import UncaughtSelfFixtures (prepareUncaughtSelf)
import MaskFunctionFixtures (prepareMaskFunctions)
import InterfaceFixtures (prepareInterfaceCore)
import OriginalStdioFixtures (prepareOriginalStdio, prepareOriginalStdioRead)
import OriginalHandleReadinessFixtures (prepareOriginalHandleReadiness)
