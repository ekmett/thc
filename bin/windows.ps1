# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
param(
    [ValidateSet('Build', 'Runtime', 'Haskell', 'Fixtures', 'Test', 'NativeLinkTest', 'ArrayTest', 'DirectoryTest', 'CodePageTest', 'WindowsServicesTest', 'MallocTest', 'CheckCore')]
    [string]$Action = 'Build',
    [ValidateRange(1, 32)][int]$Jobs = 4,
    [ValidateSet('All', 'Driver', 'Runtime')][string]$TestGroup = 'All'
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot
. "$PSScriptRoot/windows-common.ps1"
if ($env:OS -ne 'Windows_NT') { throw 'Use the native Windows host for this script' }
if (!$env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME = Join-Path $root '.gradle-user-home' }
$testDriver = $Action -eq 'Test' -and $TestGroup -in @('All', 'Driver')
$testRuntime = $Action -eq 'Test' -and $TestGroup -in @('All', 'Runtime')
# These fixture producers have unstable/shared outputs; fail before any build.
if ($Action -in @('Fixtures', 'DirectoryTest', 'CodePageTest') -or ($testDriver -and !$testRuntime)) {
    throw 'Requested Windows fixture lane is quarantined; see docs/fixture-quarantine.log'
}
if ($Action -eq 'Test') {
    Write-Warning 'Smoke/driver/directory/codepage fixtures are quarantined; running the remaining portable and ABI checks.'
}
Push-Location $root
New-Item -ItemType Directory -Force "$root/build" | Out-Null
$lease = [IO.File]::Open("$root/build/.native-windows-build.lock",
    [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
try {
    if ($Action -in @('Build', 'Runtime', 'Test', 'DirectoryTest', 'CodePageTest')) {
        Assert-ThcJava
        Invoke-ThcTool "$root/gradlew.bat" @('--no-daemon', "--max-workers=$Jobs", 'installDist', 'toolsJar')
    }
    if ($Action -in @('Build', 'Haskell', 'Fixtures', 'Test', 'NativeLinkTest', 'ArrayTest', 'DirectoryTest', 'CodePageTest', 'WindowsServicesTest', 'MallocTest', 'CheckCore')) {
        $tools = Get-ThcGhc
        $env:GHC = $tools.Compiler
        $env:GHC_PKG = $tools.PackageTool
        $cabal = if ($env:CABAL) { $env:CABAL } else { 'cabal' }
        $flags = @('--disable-shared', "-j$Jobs", '-fdevelopment',
            "--with-compiler=$($tools.Compiler)", "--with-hc-pkg=$($tools.PackageTool)")
        if ($Action -eq 'CheckCore') {
            Invoke-ThcTool (Join-Path (Split-Path $tools.Compiler) 'runghc.exe') @('-f', $tools.Compiler,
                '--ghc-arg=-package', '--ghc-arg=ghc', '--ghc-arg=-package', '--ghc-arg=Cabal',
                'bin/check-ghc-core.hs', 'check', 'ghc-internal', 'base')
        } else {
            Invoke-ThcTool $cabal (@('build', 'all') + $flags)
        }
    }
    if ($Action -in @('Fixtures', 'Test')) {
        $fixture = Invoke-ThcTool $cabal (@('list-bin', 'exe:thc-fixtures') + $flags)
    }
    if ($testRuntime -or $Action -eq 'NativeLinkTest') {
        if (!$env:THC_TEST_ROOT) { $env:THC_TEST_ROOT = $root }
        if (!$env:THC_TEST_SCRATCH) { $env:THC_TEST_SCRATCH = Join-Path $root 'build/windows-native-link-tests' }
        New-Item -ItemType Directory -Force $env:THC_TEST_SCRATCH | Out-Null
        Invoke-ThcTool $cabal (@('test', 'driver-tests', '--test-options=--package-native-only',
            '--test-show-details=direct') + $flags)
    }
    if ($testRuntime) {
        Invoke-ThcTool $cabal (@('test', 'driver-lock-tests', '--test-show-details=direct') + $flags)
    }
    $focusedTests = @()
    if ($testRuntime) {
        $env:THC_FIXTURES = $fixture
        Invoke-ThcTool $fixture @('tuple-arithmetic')
        Invoke-ThcTool $fixture @('bit')
        Invoke-ThcTool $fixture @('signed-narrow')
        $focusedTests += @('thc.runtime.TupleArithmeticTest', 'thc.runtime.WordCarryTest',
            'thc.runtime.BitPrimopsTest', 'thc.SignedNarrowPrimopsTest',
            'thc.runtime.ScalarBitCastTest.directTypedCallsPreserveBitsOnFirstCompiledCallAndRejectWrongCarriers',
            'thc.runtime.ScalarBitCastTest.typedNodesKeepRawBitsAndNeverUseBoxedOperandExecution',
            'thc.ThreadedThunkTest.sparkedWorkRunsBeforeDemandAndFirstCompiledHintsShareTheOriginalThunk',
            'thc.ThreadedThunkTest.disabledSparkHintsKeepWorkUnforcedAndDoNotAdmitAWorker',
            'thc.ThreadedThunkTest.sparkedGuestFailureIsDeferredAndDoesNotStopUnrelatedWork',
            'thc.ThreadedThunkTest.cancellingSparkWorkerLeavesTheSameThunkResumableWithoutReplayingItsEffect',
            'thc.ThreadedThunkTest.disposingSparkContextStopsClaimedWorkAndDiscardsUnstartedHints',
            'thc.runtime.AdaptiveAsyncTest.firstExternalRequestReachesRequestFreeCompiledConcurrentLoop',
            'thc.runtime.ManagedWeakTest.identityOnlyGuestWeaksCollectWhileLiveKeysAndFirstCompiledCallsPreserveIdentity',
            'thc.runtime.ManagedWeakTest.mutVarKeyValueBackReferencesCollectOnFirstCompiledCalls',
            'thc.runtime.ManagedWeakTest.liveMutVarKeysRetainDroppedRegistrationsUntilDetached',
            'thc.runtime.ManagedWeakTest.callbackAttachmentPromotesIdentityWeaksAndDependentPayloadsRemainExplicit',
            'thc.runtime.ManagedWeakTest.actualContextCloseInvalidatesHandlesWithoutRunningHaskellActions',
            'thc.runtime.ManagedWeakTest.retainedMVarRequestKeepsConditionalValueAliveUntilRequestIsDropped',
            'thc.runtime.ManagedWeakTest.ownedFreeRetiresCollectedMutVarKeysAtManagedGcRequests',
            'thc.runtime.ManagedWeakTest.ownedFreeDefersBorrowedAllocationsUntilAnotherManagedGcRequest',
            'thc.runtime.ManagedWeakTest.ownedFreePromotionPreservesCanonicalAdmissionAndNewestFirstCallbacks',
            'thc.runtime.ManagedWeakTest.ownedFreeBorrowDeferralLetsOneLoomHecRunTheBorrowerAgain',
            'thc.runtime.ManagedWeakTest.ownedFreeExplicitFinalizeRacesManagedGcWithoutReplayingRetirement',
            'thc.runtime.ManagedWeakTest.ownedFreeDrainCannotOutliveContextCancellation',
            'thc.runtime.ManagedWeakTest.finalizationIsLinearizableAndFailureDoesNotReviveRegistration',
            'thc.runtime.ManagedWeakTest.explicitCallbacksRunNewestFirstAfterDeathOutsideTheRegistryLock',
            'thc.runtime.CompilerHeapHintTest.gcCallsReturnHonestResultsFromTheFirstCompiledCall',
            'thc.runtime.ManagedMVarCellTest',
            'thc.runtime.TupleJoinLoweringTest.emptyOperandRunsInLogicalOrderBeforeParallelMovesAndFailureTransfersNothing',
            'thc.runtime.TupleJoinLoweringTest.tupleResultScratchIsClearedAfterCopyingUnlessTheDestinationAliasesIt',
            'thc.runtime.EmptyArgumentRuntimeTest.emptyInputAndLazyReferenceTupleResultUseSeparateLoansAndRecoverAfterThrow',
            'thc.runtime.EmptyArgumentRuntimeTest.exactEmptyInputsRemainDistinctFromStateContractsAndSupportedNestedZeroWidthTuples',
            'thc.runtime.EmptyArgumentRuntimeTest.ignoredScalarStateTupleFieldExecutesBeforeLaterWorkAndRejectsInvalidCarrier',
            'thc.runtime.UnknownBoxedSumTest.poisonPointerSurvivesResultsArgumentsPapCaptureCaseAndConstructor',
            'thc.runtime.TupleRepresentationTest',
            'thc.AggregateFrontierTest',
            'thc.runtime.ManagedStackRuntimeTest.rejectedDestinationsAndUnknownKeysNeverPartiallyWrite',
            'thc.runtime.ManagedStackRuntimeTest.layoutMismatchesAndMalformedIpeLayoutsFailBeforeAnyWrite',
            'thc.CoreUnitLoadTest.coldReferencesDoNotOpenOtherUnitsAndFirstDemandReusesTheBinding',
            'thc.CoreUnitLoadTest.missingOrBadColdUnitFailsOnlyAtDemandAndDoesNotTouchThirdUnit',
            'thc.NarrowPublicEntryTest.partialApplicationUsesRemainingNotOriginalInputProofs',
            'thc.runtime.NativeByteArrayPolicyTest.nativeStorageRequiresNativeAuthorityAtContextCreation',
            'thc.runtime.NativeByteArrayPolicyTest.nativeOwnerOutlivesContextWhileRegistriesAndPointerCellBoundariesStayChecked',
            'thc.runtime.NativeByteArrayPolicyTest.launcherDefaultsToNativeButHonorsHeapWithoutChangingEmbeddings',
            'thc.runtime.NativeByteArrayPolicyTest.guestCreationQueriesResizeAndAliasesRespectContextPolicyOnBothBackends',
            'thc.runtime.PinnedPointerCellsTest.orderedAddressesRequireOneAllocationAndPreserveCheckedOffsets',
            'thc.runtime.CallerContinuationProofTest.childFailureAfterSuspensionReentersCallerAndMemoizesNormally',
            'thc.runtime.NarrowIntegerCarrierTest.narrowLiteralsRetainIntrinsicValuesWhenProofsAreErased')
    }
    if ($testRuntime -or $Action -eq 'ArrayTest') {
        Assert-ThcJava
        $fixture = Invoke-ThcTool $cabal (@('list-bin', 'exe:thc-fixtures') + $flags)
        foreach ($group in @('int-arrays', 'int8-arrays', 'int16-arrays', 'int32-arrays', 'double-arrays', 'float-word-arrays')) {
            Invoke-ThcTool $fixture @($group)
        }
        $focusedTests += @('thc.runtime.IntArrayNativeTest', 'thc.runtime.Int8ArrayNativeTest',
            'thc.runtime.Int16ArrayNativeTest', 'thc.runtime.Int32ArrayNativeTest',
            'thc.runtime.DoubleArrayNativeTest', 'thc.runtime.FloatWordArrayNativeTest')
    }
    if ($testRuntime -or $Action -eq 'MallocTest') {
        Assert-ThcJava
        $fixture = Invoke-ThcTool $cabal (@('list-bin', 'exe:thc-fixtures') + $flags)
        Invoke-ThcTool $fixture @('native-addresses')
        $focusedTests += @('thc.runtime.NativeMallocTest', 'thc.runtime.WindowsStdioHostAbiTest',
            'thc.runtime.PosixStdioHostAbiModelTest', 'thc.runtime.StdioHostAbiFailureTest',
            'thc.runtime.ReturnedForeignPointerTest', 'thc.runtime.ReturnedPointerCompilationTest',
            'thc.runtime.NarrowReturnedPointerTest', 'thc.runtime.ForeignExceptionPolicyTest',
            'thc.runtime.WindowsSulongLibraryLookupTest',
            'thc.runtime.PinnedPointerCellsTest.nonOverlappingAddressCopyPreservesPointerCellsAndRejectsInvalidRegions',
            'thc.runtime.ScalarMemoryUtilitiesTest.addressRangesAndPointerCellsAreCheckedBeforeEffects')
    }
    if ($Action -eq 'WindowsServicesTest') {
        Assert-ThcJava
        $focusedTests += @('thc.runtime.PosixStdioHostAbiModelTest', 'thc.runtime.StdioHostAbiFailureTest',
            'thc.runtime.WindowsStdioHostAbiTest')
    }
    if ($testRuntime -or $Action -in @('DirectoryTest', 'CodePageTest', 'WindowsServicesTest')) {
        $focusedTests += 'thc.runtime.WindowsAbiInitializationTest'
    }
    if ($focusedTests.Count) {
        # One shared compilation and one pair of reports retain every selected
        # suite; separate invocations of a test task overwrite earlier XML.
        $gradleArgs = @('--no-daemon', "--max-workers=$Jobs", '--continue', 'installDist')
        foreach ($mode in @('testDefault', 'testDense')) {
            $gradleArgs += $mode
            foreach ($test in $focusedTests) { $gradleArgs += @('--tests', $test) }
        }
        Invoke-ThcTool "$root/gradlew.bat" $gradleArgs
    }
} finally { $lease.Dispose(); Pop-Location }
