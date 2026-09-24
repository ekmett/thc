-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

module Greeting (greeting) where

import Message (message)
import Words (suffix)

greeting :: String
greeting = message ++ suffix
