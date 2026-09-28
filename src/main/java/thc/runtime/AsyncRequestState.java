// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
public enum AsyncRequestState { PENDING, CLAIMED, PAUSED, ACKNOWLEDGED, CANCELLED, TARGET_FINISHED, FAILED }
