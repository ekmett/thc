-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE ForeignFunctionInterface, MagicHash #-}
module Main where

import Control.Concurrent (forkIO, isCurrentThreadBound, myThreadId, newEmptyMVar, putMVar, takeMVar, throwTo, yield)
import Control.Exception (Exception, bracket, evaluate, getMaskingState, mask_, try, uninterruptibleMask_)
import Data.IORef (newIORef, readIORef, writeIORef)
import Foreign (FunPtr, WordPtr(..), freeHaskellFunPtr)
import GHC.Conc (BlockReason(BlockedOnException), ThreadStatus(ThreadBlocked), threadStatus)
import GHC.Exts (Int(I#))
import qualified ThreadInventory as Audit

foreign import ccall safe "thc_callback_identity_invoke" invoke :: FunPtr (IO ()) -> IO ()
foreign import ccall unsafe "thc_callback_identity_carrier" carrier :: IO WordPtr
foreign import ccall "wrapper" wrap :: IO () -> IO (FunPtr (IO ()))

-- This oracle uses GHC's actual callback trampoline. THC compares only the
-- managed reverse-entry identity contract, not support for this C transport.
main :: IO ()
main = mapM_ observe [id, mask_, uninterruptibleMask_] >> pendingIsolation
  where
    observe masking = masking $ do
      outer <- myThreadId
      outerCarrier <- carrier
      before <- getMaskingState
      bracket (wrap $ do
        first <- myThreadId
        firstCarrier <- carrier
        firstMask <- getMaskingState
        bound <- isCurrentThreadBound
        observation <- evaluate (I# (Audit.callbackObservation 0#))
        bracket (wrap $ do
          second <- myThreadId
          secondCarrier <- carrier
          secondMask <- getMaskingState
          secondBound <- isCurrentThreadBound
          nestedObservation <- evaluate (I# (Audit.callbackObservation 7#))
          print (before, first /= outer, firstCarrier == outerCarrier, firstMask, bound,
                 second /= first && second /= outer, secondCarrier == outerCarrier,
                 secondMask, secondBound, observation, nestedObservation))
          freeHaskellFunPtr invoke)
        freeHaskellFunPtr invoke
      after <- getMaskingState
      restored <- myThreadId
      print (before == after, outer == restored)

data Probe = Probe deriving Show
instance Exception Probe

-- Wait for a real pending throwTo, not a timing assumption. Its masked target
-- remains suspended while a fresh unmasked callback executes on the carrier.
pendingIsolation :: IO ()
pendingIsolation = do
  caller <- myThreadId
  acknowledged <- newEmptyMVar
  callback <- newIORef False
  returned <- newIORef False
  outcome <- try (uninterruptibleMask_ $ do
    sender <- forkIO (throwTo caller Probe >> putMVar acknowledged ())
    let awaitPending :: Int -> IO ()
        awaitPending 0 = error "throwTo sender never blocked"
        awaitPending fuel = do
          status <- threadStatus sender
          if status == ThreadBlocked BlockedOnException then pure ()
          else yield >> awaitPending (fuel - 1)
    awaitPending 1000000
    bracket (wrap $ do
      self <- myThreadId
      yield
      status <- threadStatus sender
      writeIORef callback (self /= caller && status == ThreadBlocked BlockedOnException))
      freeHaskellFunPtr invoke
    status <- threadStatus sender
    writeIORef returned (status == ThreadBlocked BlockedOnException)) :: IO (Either Probe ())
  takeMVar acknowledged
  observedCallback <- readIORef callback
  observedReturn <- readIORef returned
  print (case outcome of Left Probe -> True; Right () -> False,
         observedCallback, observedReturn, True)
