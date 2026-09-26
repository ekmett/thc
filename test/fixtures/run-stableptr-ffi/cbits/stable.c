/* SPDX-FileCopyrightText: 2026 Edward Kmett
 * SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause */
#include "stable.h"
static void *saved;
static unsigned char unrelated;
void stable_store(void *pointer) { saved = pointer; }
void *stable_load(void) { return saved; }
void *stable_identity(void *pointer) { return pointer; }
int stable_equal(void *left, void *right) { return left == right; }
void stable_clear(void) { saved = 0; }
void *stable_unknown(void) { return &unrelated; }
