/* SPDX-FileCopyrightText: 2026 Edward Kmett
 * SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause */
#ifndef THC_STABLE_FIXTURE_H
#define THC_STABLE_FIXTURE_H
void stable_store(void *pointer);
void *stable_load(void);
void *stable_identity(void *pointer);
int stable_equal(void *left, void *right);
void stable_clear(void);
void *stable_unknown(void);
#endif
