-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
module ScalarBitcodeTests (tests) where

import Data.Either (isLeft)
import Test.HUnit
import THC.Driver.ScalarBitcode (parseDependencies, scalarFunctions, sulongScalarTarget)

tests :: Test
tests = TestLabel "closed scalar C producer inputs" $ TestList
  [ TestCase $ assertEqual "Clang dependency escaping and full header inventory"
      (Right ["cbits/scalar.c","include/a b.h","system/header.h"])
      (parseDependencies "thc_scalar_input: cbits/scalar.c \\\n include/a\\ b.h system/header.h\n")
  , TestCase $ mapM_ (assertBool "ambiguous Make syntax rejected" . isLeft . parseDependencies)
      ["wrong: source.c\n","thc_scalar_input: $(input)\n","thc_scalar_input: source.c\nother: x\n"]
  , TestCase $ assertEqual "exact scalar LLVM types, with internal helpers excluded from foreign ABI"
      (Right [("mixed",["Int32Rep","Int64Rep","FloatRep","DoubleRep"],"DoubleRep")])
      (scalarFunctions $ unlines
        ["define internal i32 @helper(i32 noundef %x) {","  ret i32 %x","}",
         "define dso_local noundef double @mixed(i32 noundef %a, i64 %b, float %c, double returned %d) {",
         "  ret double %d","}"])
  , TestCase $ mapM_ (\(label,ir) -> assertBool label (isLeft (scalarFunctions ir)))
      [("signext result rejected", "define signext i32 @result(i32 %x) {\n ret i32 %x\n}"),
       ("zeroext result rejected", "define zeroext i32 @result(i32 %x) {\n ret i32 %x\n}"),
       ("signext parameter rejected", "define i32 @argument(i32 signext %x) {\n ret i32 %x\n}"),
       ("zeroext parameter rejected", "define i32 @argument(i32 zeroext %x) {\n ret i32 %x\n}")]
  , TestCase $ mapM_ (assertBool "nonclosed/native ABI products rejected" . isLeft . scalarFunctions)
      [ unlines ["declare i32 @external(i32)",integerFunction]
      , unlines ["@state = global i32 0",integerFunction]
      , unlines ["@state = thread_local global i32 0",integerFunction]
      , unlines ["@llvm.global_ctors = appending global [0 x ptr] []",integerFunction]
      , "define i32 @pointer(ptr %p) {\n ret i32 0\n}"
      , "define i32 @variadic(i32 %x, ...) {\n ret i32 %x\n}"
      , "define fastcc i32 @wrongcc(i32 %x) {\n ret i32 %x\n}"
      , "define weak i32 @interposed(i32 %x) {\n ret i32 %x\n}"
      , "define i32 @numeric(i64 %x) {\n %p = inttoptr i64 %x to ptr\n ret i32 0\n}"
      , "define i32 @callback(i32 %x) {\n %v = call i32 %fp(i32 %x)\n ret i32 %v\n}"
      ]
  , TestCase $ do
      assertEqual "exact Sulong Linux x86_64 vendor alias" "x86_64-unknown-linux-gnu"
        (sulongScalarTarget "x86_64-pc-linux-gnu")
      mapM_ (\target -> assertEqual ("target retained: " ++ target) target (sulongScalarTarget target))
        ["x86_64-unknown-linux-gnu", "aarch64-pc-linux-gnu", "i386-pc-linux-gnu",
         "x86_64-pc-linux-musl", "x86_64-pc-linux-gnux32", "x86_64-pc-windows-msvc",
         "x86_64-apple-darwin", "x86_64-redhat-linux-gnu", "x86_64-pc-linux-gnu-extra"]
  ]
  where integerFunction = "define i32 @identity(i32 %x) {\n ret i32 %x\n}"
