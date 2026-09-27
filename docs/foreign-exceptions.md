# Foreign exception bridge

The runtime library defines an abstract THC.Exception.ForeignException with a
normal, compiler-generated Exception instance. Pure Show/displayException are
inert: they do not execute foreign display code. Type and message inspection
return IO (Maybe String) and may execute foreign code.

This checkpoint supplies the source API and compiler/linker evidence. Automatic
translation at the JVM foreign execution boundary is a separate runtime change;
adding the library alone does not make foreign failures catchable.

The defining THC.Internal.Exception module exports opaque boxForeign and
projectForeign helpers for the runtime linker. Their genuine Any and
SomeException signatures are checked by GHC before the plugin emits a
foreignExceptionBridge record with exact unit-qualified identities. Public
handlers import THC.Exception; the internal constructor and raw helper interface
are marked Unsafe. The public wrapper is Trustworthy and usable by a Safe client.

The plugin also records exceptionPayload on raise# and raiseIO# only when GHC's
actual payload type is the pinned SomeException type. Consumers without this
support ignore the additional metadata. The automatic runtime uses it to
distinguish genuine Haskell exceptions from opaque primitive payloads without
forcing arbitrary values. Old captures lacking the marker remain opaque; their
exception values must not be guessed from runtime shapes.

Native GHC's metadata compatibility call returns unavailable. It does not invent
foreign exceptions or change ordinary Haskell exception behavior.

Focused validation:

* Build the plugin and export runtime/THC/Exception.hs after Tidy.
* Compile/run compiler/test-fixtures/ForeignExceptionNative.hs with
  runtime/exception.c: expected (42,77,99,1).
* Compile ForeignExceptionSafe.hs with -fno-code -iruntime.
* ForeignExceptionUnsafeImport.hs must fail because the internal module cannot
  be safely imported.
