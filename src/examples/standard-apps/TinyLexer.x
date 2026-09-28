{
-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
module Main (main) where
}
%wrapper "basic"
$letter = [a-zA-Z]
$digit = [0-9]
tokens :-
  $white+ ;
  $letter [$letter $digit]* { id }
  $digit+ { id }
  \+ { id }
{
main :: IO ()
main = print (alexScanTokens "sum + 42")
}
