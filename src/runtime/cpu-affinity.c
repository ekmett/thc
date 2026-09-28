/* SPDX-FileCopyrightText: 2026 Edward Kmett
 * SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause */

/* Native GHC compatibility: normal forkOn alone establishes no CPU-affinity
 * guarantee. THC recognizes only these exact versioned query declarations. */
int thc_cpu_affinity_v1_support(void) { return 0; }
int thc_cpu_affinity_v1_applied(void) { return 0; }
