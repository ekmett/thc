-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
module Main (main) where

import Foreign.C.String (CString)
import Foreign.C.Types (CChar)
import Foreign.Marshal.Array (withArray0)
import System.Exit (die)

-- These imports exercise the native symbols independently of the synthetic
-- caller used for the JVM's genuine retained Core descriptors.
foreign import ccall unsafe "strlen" c_strlen :: CString -> IO Int
foreign import ccall unsafe "rts_isThreaded" c_rtsIsThreaded :: IO Int

main :: IO ()
main = do
  lengths <- mapM (\bytes -> withArray0 0 (map fromIntegral bytes :: [CChar]) c_strlen)
    ([[], [65], [206, 187, 195, 169]] :: [[Int]])
  threaded <- c_rtsIsThreaded
  if lengths == [0, 1, 4] && threaded == 0
    then print (lengths, threaded)
    else die ("Unexpected native strlen/nonthreaded RTS observations: " ++ show (lengths, threaded))
