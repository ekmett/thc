{-# LANGUAGE ForeignFunctionInterface #-}
{-# LANGUAGE InterruptibleFFI #-}
module Main (main) where

import Control.Exception (bracket)
import Foreign
import Foreign.C
import System.Environment (getArgs, getExecutablePath, getEnv, lookupEnv, setEnv, unsetEnv)
import System.Exit
import System.IO
import System.Posix.Types (CPid(..))
import System.Process

-- These are the genuine process-package C symbols with their original safety,
-- not a native oracle linked to THC's implementation.
foreign import ccall unsafe "getProcessExitCode"
  pollProcess :: CPid -> Ptr CInt -> IO CInt
foreign import ccall interruptible "waitForProcess"
  waitProcess :: CPid -> Ptr CInt -> IO CInt
foreign import ccall unsafe "terminateProcess"
  stopProcess :: CPid -> IO CInt

row :: String -> CInt -> CInt -> IO ()
row name status code = do
  Errno err <- getErrno
  putStrLn $ unwords [name, show status, show code, show err]

call :: String -> (CPid -> Ptr CInt -> IO CInt) -> CPid -> IO ()
call name operation pid = alloca $ \cell -> do
  poke cell 991
  resetErrno
  status <- operation pid cell
  code <- peek cell
  row name status code

withChild :: [String] -> ((Handle, Handle, ProcessHandle, CPid) -> IO a) -> IO a
withChild args action = do
  self <- getExecutablePath
  bracket (createProcess (proc self args) { std_in = CreatePipe, std_out = CreatePipe })
    (\(input, output, _, ph) -> do
      mapM_ hClose input
      mapM_ hClose output
      -- getProcessExitCode intentionally tolerates an already raw-reaped child.
      status <- getProcessExitCode ph
      case status of
        Just _ -> pure ()
        Nothing -> terminateProcess ph >> waitForProcess ph >> pure ()) $ \(Just input, Just output, _, ph) -> do
        Just pid <- getPid ph
        action (input, output, ph, pid)

oracle :: IO ()
oracle = do
  withChild ["hold"] $ \(input, output, _, pid) -> do
    _ <- hGetChar output
    call "running" pollProcess pid
    hPutChar input 'x' >> hFlush input
    call "wait" waitProcess pid
    call "poll-after-wait" pollProcess pid
    call "wait-after-wait" waitProcess pid
  withChild ["hold"] $ \(_, output, _, pid) -> do
    _ <- hGetChar output
    resetErrno
    status <- stopProcess pid
    row "terminate" status 991
    call "terminated-wait" waitProcess pid
  withChild ["exit", "17"] $ \(_, output, _, pid) -> do
    -- EOF provides a bounded blocking synchronization, not a timing guess.
    _ <- hGetContents output >>= \bytes -> length bytes `seq` pure ()
    let untilExited = do
          alloca $ \cell -> do
            resetErrno
            status <- pollProcess pid cell
            if status == 0 then untilExited else peek cell >>= row "poll-exit" status
    untilExited
    call "poll-after-poll" pollProcess pid
    call "wait-after-poll" waitProcess pid
  bracket (lookupEnv "PATH") (maybe (unsetEnv "PATH") (setEnv "PATH")) $ \_ -> do
    setEnv "PATH" "/bin"
    -- libc spawnp searches the parent's PATH, not the child's env override.
    (_, _, _, ph) <- createProcess (proc "true" []) { env = Just [("PATH", "/missing-child-path")] }
    status <- waitForProcess ph
    case status of
      ExitSuccess -> resetErrno >> row "parent-path" 0 0
      _ -> fail "native parent PATH lookup failed"

main :: IO ()
main = getArgs >>= \args -> case args of
  ["oracle"] -> oracle
  ["exit", code] -> exitWith (if code == "0" then ExitSuccess else ExitFailure (read code))
  ["hold"] -> do
    hPutChar stdout 'R' >> hFlush stdout
    _ <- hGetChar stdin
    exitWith (ExitFailure 23)
  ["environment"] -> getEnv "THC_CHILD_VALUE" >>= putStrLn
  ["pipes"] -> do
    text <- hGetLine stdin
    hPutStrLn stdout ("out:" ++ text)
    hPutStrLn stderr ("err:" ++ text)
  _ -> fail "process fixture: expected oracle, exit, hold, environment, or pipes"
