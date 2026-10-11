// SPDX-FileCopyrightText: 2026 Edward Kmett <ekmett@gmail.com>
// SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0
#include "pin_writer.h"
#ifdef _WIN32
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#else
#include <pthread.h>
#endif
#include <stdatomic.h>
#include <stdlib.h>

struct writer {
#ifdef _WIN32
  HANDLE thread;
#else
  pthread_t thread;
#endif
  uint64_t * payload;
  uint64_t count;
  atomic_int stop;
};
#ifdef _WIN32
static DWORD WINAPI run(void * argument) {
#else
static void * run(void * argument) {
#endif
  struct writer * writer = argument;
  uint64_t count = 0;
  while (!atomic_load_explicit(&writer->stop, memory_order_relaxed))
    __atomic_store_n(writer->payload, ++count, __ATOMIC_RELAXED);
  writer->count = count;
  return 0;
}
void * thc_pin_start(void * payload) {
  struct writer * writer = calloc(1, sizeof(*writer));
  if (!writer) return NULL;
  writer->payload = payload;
  atomic_init(&writer->stop, 0);
#ifdef _WIN32
  writer->thread = CreateThread(NULL, 0, run, writer, 0, NULL);
  if (!writer->thread) { free(writer); return NULL; }
#else
  if (pthread_create(&writer->thread, NULL, run, writer)) { free(writer); return NULL; }
#endif
  return writer;
}
int64_t thc_pin_stop(void * argument) {
  struct writer * writer = argument;
  atomic_store_explicit(&writer->stop, 1, memory_order_relaxed);
#ifdef _WIN32
  if (WaitForSingleObject(writer->thread, INFINITE) != WAIT_OBJECT_0 || !CloseHandle(writer->thread)) abort();
#else
  if (pthread_join(writer->thread, NULL)) abort();
#endif
  int64_t count = (int64_t) writer->count;
  free(writer);
  return count;
}
