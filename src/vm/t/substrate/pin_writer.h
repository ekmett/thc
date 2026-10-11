// SPDX-FileCopyrightText: 2026 Edward Kmett <ekmett@gmail.com>
// SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0
#pragma once
#include <stdint.h>
void * thc_pin_start(void * payload);
int64_t thc_pin_stop(void * writer);
