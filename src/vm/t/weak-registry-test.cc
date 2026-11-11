// SPDX-FileCopyrightText: 2026 Edward Kmett <ekmett@gmail.com>
// SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <new>
#include <span>
import thc.jam;

static std::ptrdiff_t fail_after = -1;
void * operator new(std::size_t bytes) {
  if (fail_after == 0) throw std::bad_alloc{};
  if (fail_after > 0) --fail_after;
  if (void * p = std::malloc(bytes ? bytes : 1)) return p;
  throw std::bad_alloc{};
}
void operator delete(void * p) noexcept { std::free(p); }
void operator delete(void * p, std::size_t) noexcept { std::free(p); }

void check(bool ok, char const * why) noexcept {
  if (!ok) { std::fprintf(stderr, "%s\n", why); std::abort(); }
}
void reuse() {
  thc::weak_registry registry;
  auto previous = registry.create(1, 2, 3);
  check(registry.finalize(previous) == 3, "initial finalizer claim");
  registry.complete(previous);
  fail_after = 0;
  for (unsigned i = 0; i != 10000; ++i) {
    auto const id = registry.create(4, 5, 6);
    check(id && id != previous && !(id >> 63), "reused record has a fresh positive token");
    check(!registry.value(0) && !registry.value(id | (1ull << 63)), "invalid tokens are rejected");
    check(!registry.value(previous) && !registry.finalize(previous), "retired token stays dead");
    registry.complete(previous);
    check(registry.value(id) == 5 && registry.finalize(id) == 6, "stale completion cannot consume a new finalizer");
    registry.complete(id);
    previous = id;
  }
  fail_after = -1;
  auto const a = registry.create(1, 2, 3);
  auto const b = registry.create(4, 5, 6);
  check(a && b && a != b, "different slots have distinct opaque tokens");
  registry.complete(previous);
  check(registry.value(a) == 2 && registry.value(b) == 5, "old tokens cannot affect either occupied slot");
}
void allocation_failure() {
  bool succeeded = false;
  for (unsigned failure = 0; failure != 8; ++failure) {
    thc::weak_registry registry;
    auto const old = registry.create(1, 2, 3);
    fail_after = failure;
    auto const replacement = registry.create(4, 5, 6);
    fail_after = -1;
    check(registry.value(old) == 2, "failed replacement preserves existing value");
    check(registry.finalize(old) == 3, "failed replacement preserves cleanup ownership");
    registry.complete(old);
    if (replacement) { succeeded = true; break; }
    auto const retry = registry.create(4, 5, 6);
    check(retry && registry.value(retry) == 5, "failed growth leaves registry reusable");
  }
  check(succeeded, "exercised every registration allocation failure");
}
void traversal() {
  thc::weak_registry registry;
  auto const second = registry.create(2, 4, 5);
  auto const first = registry.create(1, 2, 3);
  auto const dead = registry.create(6, 7, 8);
  std::uint32_t marked = 1u << 1;
  auto trace = [&](std::span<std::uint32_t const> roots) noexcept {
    for (auto root : roots) if (root) marked |= 1u << root;
  };
  auto live = [&](std::uint32_t at) noexcept { return (marked >> at) & 1u; };
  fail_after = 0;
  registry.begin(); registry.roots(trace); registry.close(live, trace);
  check(registry.value(first) == 2 && registry.value(second) == 4, "least fixed point survives without allocation");
  check(!registry.value(dead) && !live(8), "dead batch retires before finalizer rescue");
  registry.roots(trace);
  check(live(8), "queued finalizer remains rooted");
  std::uint64_t token;
  check(registry.take(token) == 8 && token == dead, "queued finalizer claim");
  marked = 0;
  registry.begin(); registry.roots(trace); registry.close(live, trace);
  check(live(8), "running finalizer survives a nested collection");
  registry.complete(dead);
  registry.forward([](std::uint32_t at) noexcept { return at; });
  fail_after = -1;
}
int main() {
  reuse(); allocation_failure(); traversal();
  std::puts("weak registry reuse, failure atomicity and allocation-free traversal passed");
}
