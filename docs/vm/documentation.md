# Runtime documentation

Edit the guides in `docs/vm/` and the component overview in `src/vm/README.md`.
They belong to THC's documentation; see [contributing](../contributing.md)
for `make docs` and `make docs-check`.

The Java API reference is produced by `src/vm/tools/build_bridge.py`, alongside
the JNI bridge and API JAR. It needs a JDK 25 installation and the platform C
compiler, but does not build HotSpot, Graal or the collector.
