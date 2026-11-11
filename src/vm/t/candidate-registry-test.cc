// SPDX-FileCopyrightText: 2026 Edward Kmett <ekmett@gmail.com>
// SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <new>
#include <span>
import thc.jam;

static int fail_after = -1;
void * operator new(std::size_t n) {
  if (fail_after == 0) throw std::bad_alloc{};
  if (fail_after > 0) --fail_after;
  if (auto * p = std::malloc(n ? n : 1)) return p;
  throw std::bad_alloc{};
}
void operator delete(void * p) noexcept { std::free(p); }
void operator delete(void * p, std::size_t) noexcept { std::free(p); }
static void check(bool ok, char const * what) {
  if (!ok) { std::fprintf(stderr, "FAIL: %s\n", what); std::abort(); }
}
static void lifecycle() {
  thc::candidate_registry r;
  check(r.empty() && !r.epoch(), "empty registry has no publication");
  check(!r.arm(0, 1) && !r.arm(1, 0) && !r.arm(1, 1ull << 63), "reject null/invalid wait");
  auto a = r.arm(1, 7);
  check(a && !r.poll(a, 7), "armed owner cannot be polled");
  check(!r.disarm(a, 8) && !r.disarm(a, 0), "wrong wait cannot claim");
  r.complete(a, 7);
  check(r.disarm(a, 7) == 1, "complete cannot retire unclaimed owner");
  check(!r.poll(a, 7) && !r.disarm(a, 7), "one claimant only");
  unsigned roots = 0;
  auto trace = [&](std::span<std::uint32_t const> xs) noexcept { for (auto x : xs) roots |= 1u << x; };
  r.roots(false, trace);
  check(roots == 2, "claimed owner remains rooted");
  r.forward([](std::uint32_t x) noexcept { return x + 1; });
  r.complete(a, 8); roots = 0; r.roots(false, trace);
  check(roots == 4, "wrong complete preserves relocated root");
  r.complete(a, 7); r.complete(a, 7);
  check(r.empty(), "completion releases claim idempotently");
  for (unsigned i = 0; i != 10000; ++i) {
    fail_after = 0;
    auto b = r.arm(3, 7);
    check(b && b != a && !(b >> 63), "recycled slots have positive fresh tickets without allocation");
    r.complete(a, 7);
    check(!r.disarm(a, 7) && r.disarm(b, 7) == 3, "stale ticket cannot affect reused slot");
    r.complete(b, 7); a = b;
  }
  fail_after = -1;
}
static void failure() {
  bool succeeded = false;
  for (int n = 0; n != 8; ++n) {
    thc::candidate_registry r;
    auto a = r.arm(1, 1);
    fail_after = n; auto b = r.arm(2, 2); fail_after = -1;
    check(r.disarm(a, 1) == 1, "allocation failure preserves prior ownership");
    r.complete(a, 1);
    if (b) { succeeded = true; break; }
    b = r.arm(2, 2);
    check(b && r.disarm(b, 2) == 2, "failed growth leaves registry reusable");
  }
  check(succeeded, "covered registration allocation failures");
}
static void phases() {
  thc::candidate_registry r;
  thc::weak_registry w;
  auto a = r.arm(1, 1), b = r.arm(2, 2), c = r.arm(3, 3);
  auto weak = w.create(1, 4, 5);
  auto reachable = w.create(6, 3, 0);
  unsigned marks = 0;
  auto trace = [&](std::span<std::uint32_t const> xs) noexcept {
    for (auto x : xs) if (x) { marks |= 1u << x; if (x == 1) marks |= 1u << 2; }
  };
  auto marked = [&](std::uint32_t x) noexcept { return (marks >> x) & 1u; };
  fail_after = 0;
  r.roots(true, trace);
  check(marked(1) && marked(2) && marked(3) && !r.poll(a, 1), "minors retain all without selecting");
  marks = 1u << 6; w.begin(); r.roots(false, trace);
  check(!marked(1) && !marked(2) && !marked(3), "armed owners do not root major");
  w.trace_live(marked, trace);
  check(marked(3) && w.value(reachable) == 3, "ordinary weak closure precedes selection");
  r.select(marked, trace);
  check(marked(1) && marked(2) && !r.epoch(), "selection retains batch but does not publish");
  w.close(marked, trace);
  check(w.value(weak) == 4 && marked(4) && marked(5), "rescued key activates weak before retirement");
  // Restart collection before finish: selections remain roots, and publication is still due.
  marks = 1u << 6; w.begin(); r.roots(false, trace);
  w.trace_live(marked, trace); r.select(marked, trace); w.close(marked, trace);
  r.forward([](std::uint32_t x) noexcept { return x + 8; });
  r.publish();
  check(r.epoch() == 1, "retry preserves pending epoch");
  r.publish(); check(r.epoch() == 1, "no spurious epoch without newly selected batch");
  check(r.poll(a, 1) == 9 && r.poll(b, 2) == 10, "batch snapshot selects even mutually reachable owners");
  check(!r.poll(c, 3) && r.disarm(c, 3) == 11, "ordinary weakly activated owner was not selected");
  r.complete(a, 1); r.complete(b, 2); r.complete(c, 3);
  check(r.empty(), "all transferred owners release");
  fail_after = -1;
}
int main() {
  lifecycle(); failure(); phases();
  std::puts("candidate ownership, reuse, failure atomicity, weak ordering and retry epoch passed");
}
