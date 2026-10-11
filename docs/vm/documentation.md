# Runtime documentation

The runtime guides are part of Jam's Doxygen site. From the repository root,
configure with `-DJAM_BUILD_DOCS=ON`, then run:

```sh
cmake --build build --target jam-docs
```

Open `build/docs/html/index.html`. See [Building](../building.md) for the
compiler and Doxygen requirements. Java, Pandoc and prepared VM sources are
not needed to build the documentation.

Edit the guides in `docs/vm/` and the runtime overview in `vm/README.md`.
The root [CI workflow](https://github.com/ekmett/jam/blob/main/.github/workflows/ci.yml) checks the combined site
and publishes it to [Jam's documentation](https://ekmett.github.io/jam/).
