# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
# Publish captured stdout only after successful execution; a failed command must
# not truncate an older oracle or leave a partial new oracle looking complete.
execute_process(COMMAND "${PROGRAM}" ${ARGS} OUTPUT_FILE "${OUTPUT}.tmp" RESULT_VARIABLE result)
if(NOT result STREQUAL "0")
  file(REMOVE "${OUTPUT}.tmp")
  message(FATAL_ERROR "${PROGRAM} failed: ${result}")
endif()
file(RENAME "${OUTPUT}.tmp" "${OUTPUT}")
