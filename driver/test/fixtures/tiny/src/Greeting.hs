module Greeting (greeting) where

import Message (message)
import Words (suffix)

greeting :: String
greeting = message ++ suffix
