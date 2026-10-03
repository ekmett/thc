#!/bin/sh
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

# The old ordered blanket recipe is quarantined, not a CMake producer.
printf '%s\n' 'Blanket fixture preparation is quarantined: see docs/fixture-quarantine.log.' \
  'Use make test TESTS=<exact nonquarantined class> while the file dependency graph is built.' >&2
exit 2
