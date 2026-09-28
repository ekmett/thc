{
-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
module Main (main) where
}
%name parse
%tokentype { Char }
%error { parseError }
%token one { '1' }
       '+' { '+' }
%%
Expression :: { Int }
  : Expression '+' one { $1 + 1 }
  | one { 1 }
{
parseError :: [Char] -> a
parseError _ = error "invalid tiny expression"

main :: IO ()
main = print (parse "1+1+1")
}
