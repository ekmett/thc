# Foreign exceptions

THC automatically translates catchable foreign language failures at admitted
foreign execution boundaries into `THC.Exception.ForeignException`. Haskell
`catch`, `try`, `finally`, and ordinary `throwIO` use the genuine compiled
`Exception` instance. No explicit lifting scope is required.

The public type is abstract. `foreignExceptionType` and
`foreignExceptionMessage` return `IO (Maybe String)`: inspecting foreign
metadata can execute foreign code, reenter Haskell, or raise another exception.
The metadata declaration is explicitly `safe`. Successful text snapshots are
published atomically; concurrent first readers may query independently, and the
first successful snapshot is retained. No monitor is held across foreign code.
Pure `Show` and `displayException` return inert text.

An uncaught foreign exception leaving through a compatible Polyglot entry or
managed export is restored to the original foreign object. This also applies to
`catch` followed by ordinary `throwIO`, including a caught `SomeException`
stored and thrown later. Each program retains its exact GHC runtime unit and
dictionary identity. Normalization routes a proven `SomeException` to its
authenticated program storage domain before invoking the genuine Haskell
`fromException` projector. It does not try unrelated projectors.
The context keeps weak projector references; a live foreign origin retains the
projector it needs.

Runtime errors and dynamic-source parse errors are catchable. EXIT, INTERRUPT,
cancellation, VM-fatal failures, runtime invariant failures, and internal control
transfers retain their existing meaning. Eligibility uses Truffle's exception
classification protocol, never foreign display/message/cause/stack accessors.
A broken ordinary classifier preserves the original failure; a classifier's own
cancellation or internal transfer is not swallowed. Errors thrown by the
classification protocol itself propagate as infrastructure failures; an
application AssertionError transported as an ordinary host runtime exception
remains eligible for Haskell handling.

Checked interop argument/dispatch errors are a separate ABI policy and are not
claimed as original foreign language throwables. Native error-code/errno APIs
retain their ordinary `IOError` adapters. There is no exception transport across
an arbitrary plain-C export ABI, and cause/stack inspection is not exposed yet.
Native GHC's compatibility metadata call returns unavailable.

## Linking and payload metadata

The driver automatically supplies the genuine runtime component when needed,
or reuses the application's existing exact runtime unit. Raw Core embedding must
supply the linked support and its unambiguous proof, or select
`foreignExceptionBridgeUnit` explicitly. Missing or ambiguous support is a
load/link error for foreign execution.

The defining `THC.Internal.Exception` module exports opaque `boxForeign` and
`projectForeign` helpers. GHC validates their actual `Any` and `SomeException`
types before the plugin emits unit-qualified `foreignExceptionBridge` evidence.
The internal module is `Unsafe`; the audited public wrapper is `Trustworthy`
and can be imported by a Safe client.

The exporter records `exceptionPayload` on `raise#` and `raiseIO#` only when
GHC identifies their payload as the pinned `SomeException` type. This preserves
primitive exception laziness: raw values and raw bottoms remain opaque even
after the context has handled a foreign exception. Without that marker, the payload stays opaque at public exit. Re-export the
relevant modules when transparent rethrow normalization is required; the runtime
does not guess missing provenance.
