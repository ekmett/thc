# GHCi capture adapter

`THC.Interactive` uses the pinned GHC 9.14.1 session, parser, renamer,
typechecker, desugarer, simplifier and Tidy implementation. This first checkpoint
captures Core. It does **not** execute it in THC or provide a working REPL.
There is no replacement parser or command loop.

Run the small GHC-only proof from the repository root:

```sh
python3 compiler/interactive/check.py --ghc /path/to/ghc-9.14.1 --output build/interactive-proof
```

Choose a new output directory for each run. Compilation/capture logs, 23 JSON
captures and a compact source/output hash summary remain there. No JVM is used.
The harness checks:

- GHC's statement plans for `let`, pattern bindings, displayed expressions and IO;
- distinct shadowed `Name`s, old closure references and ordered returned binding IDs;
- ordinary type errors without changing the name epoch, and stale-epoch rejection;
- lazy bottom and user IO captured without evaluation; attempted native statement
  execution is rejected by an installed compiler hook;
- declarations through post-Tidy Core, including constructor metadata, an instance
  and a fixity that is required for a following statement to typecheck;
- ordinary GHC module load, unchanged load and changed reload, retaining whole
  post-Tidy bodies including a bottom that is not evaluated. Unchanged loads
  follow GHC's actual recompilation decision; the adapter does not impose a cache.

The proof explicitly simulates successful backend installation when it commits
GHC's type context. No native value or THC value is installed by those commits.
This is sufficient to test subsequent typechecking and identity resolution, not
runtime values, CAF sharing or IO behavior.

## Integration seams

The adapter follows the frontend portions of
[`hscParsedStmt` and `hscParsedDecls`](https://github.com/ghc/ghc/blob/ghc-9.14.1-release/compiler/GHC/Driver/Main.hs#L2436).
Statements use `tcRnStmt`'s genuine `IO [Any]` action and externalised result IDs.
They then use `simplifyExpr`/`tidyExpr`, as
[`hscCompileCoreExpr'`](https://github.com/ghc/ghc/blob/ghc-9.14.1-release/compiler/GHC/Driver/Main.hs#L2715)
does, stopping before CorePrep and native code generation. Desugared and tidied
captures have different explicit boundary labels. Each executable root has a
fresh interactive module, including multiple captures in the same name epoch.

Declarations use `tcRnDeclsi`, `hscDesugar'`, `hscSimplify` and `hscTidy`, then build
the same `InteractiveContext` as GHC. They omit native bytecode loading and static
pointer registration. `installModuleCapture` wraps the existing `runPhaseHook`
and observes actual `T_HscPostTc` / `HscRecomp` results. It delegates all phases to
the previous hook or GHC's ordinary `runPhase`.

The existing rich serializer is reused through `serializeInteractiveCore`.
`interactiveBindings` records the ordered external IDs associated with the
statement action's returned list. These are distinct from the local bindings
inside that action. Statement/declaration groups are not labelled complete
source modules. Loaded modules are captured in full after Tidy.

The eventual UI seam remains the original GHCi implementation:
[`GHCi.UI.Monad.runStmt` / `runDecls'`](https://github.com/ghc/ghc/blob/ghc-9.14.1-release/ghc/GHCi/UI/Monad.hs#L370).
Reuse its command classification, imports, flags, multiline handling and module
loading. Those executable-owned UI modules are not vendored in this checkpoint.
The Cabal integration needs the `THC.Interactive` module and existing THC compiler
modules, with the pinned `ghc` library; the proof additionally uses `exceptions`,
`directory` and `filepath`. It does not require a new top-level driver layout.

## Persistent backend interface to implement next

Use one long-lived THC session and a serialized sequence of frontend/backend
transactions. The minimum protocol is:

1. `InstallModules(epoch, completeModules)` installs the successfully loaded GHC
   module set without evaluating CAFs. A load/reload advances the backend epoch
   and applies GHCi's CAF/reset policy; old interactive bindings cannot be reused
   against the new epoch.
2. `InstallDeclarations(epoch, transaction, core)` installs a complete declaration
   group, constructor classes and lazy bindings. Only acknowledgement permits
   `commitDeclarationsContext`.
3. `ExecuteStatement(epoch, transaction, root, core, resultIds)` runs the genuine
   GHC-produced action exactly once and retains its returned guest values in order.
   Success acknowledges exact IDs/opaque guest handles, then permits
   `commitStatementContext`. A failure commits no frontend bindings. Never retry a
   request whose execution status is unknown; query its transaction outcome.
4. Later Core resolves old exact IDs to the same guest cells, including memoized
   failures. Shadowing changes name lookup; it does not replay or replace old
   closure captures. Release values only under explicit reload/session lifetime.

No tuple leaves, GHC heap addresses or fake `ForeignHValue`s belong in this
protocol. GHC chooses `Show`, `print`, IO binding and `it`; the backend executes
that actual Core rather than independently reconstructing their semantics.
The name-index check in this prototype catches commits after newer bindings. It
is not a complete transaction guard across imports, flags or reload: the caller
must serialize frontend operations, and the persistent protocol must add its own
session/epoch/transaction validation before executing or committing anything.

The missing work is the persistent THC value/CAF table, the action/result bridge,
real printing/IO dependencies and synchronization with successful or failed
module loads. Debugger heap inspection, GHC runtime type reconstruction, static
pointers, Template Haskell using guest values and cross-runtime FFI remain
unsupported. `rejectNativeEvaluation` rejects native statement compilation and
TH execution for this capture harness; this is not a sandbox for arbitrary
plugins, foreign code, compiler options or module build hooks.
