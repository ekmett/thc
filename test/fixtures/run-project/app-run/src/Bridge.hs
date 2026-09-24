-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

module Bridge (expected) where
import Answer (answerValue)
import qualified Paths_app_run
expected :: Int
expected = answerValue
versionText :: String
versionText = show Paths_app_run.version
