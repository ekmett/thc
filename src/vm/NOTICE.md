# Notices

Original jam-vm code is licensed under **BSD-2-Clause OR Apache-2.0**;
see [LICENSE.md](LICENSE.md). Imported files keep their own license notices.
The prepared `upstream/` trees are fetched separately and retain their upstream
licenses. Changes to OpenJDK in `patches/hotspot-jam.patch` and
`patches/labsjdk-compat.patch` retain the notices and terms of the corresponding
OpenJDK files.

Changes to Graal in `patches/graal-jam.patch` retain the notices and terms of
the corresponding Graal files. Added sources in `src/graal/` retain their own
per-file license notices. Packaged runtimes include the upstream JDK and
Graal notices, Jam and native licenses, and the LLVM runtime license under
`legal/`. LLVM's libc++, libc++abi and libunwind use Apache-2.0 with LLVM
exceptions; see the bundled license for their complete terms.
Windows packages include the LLVM compiler-runtime license and the Microsoft
redistribution documents supplied with their MSVC runtime DLLs.
