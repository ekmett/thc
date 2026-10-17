// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0

#pragma once
#include <cstddef>
#if defined(_WIN32)
#define NOMINMAX
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#else
#include <sys/mman.h>
#include <unistd.h>
#endif

namespace test {
inline std::size_t page_size() noexcept {
#if defined(_WIN32)
  SYSTEM_INFO info{};
  GetSystemInfo(&info);
  return info.dwPageSize;
#else
  return static_cast<std::size_t>(getpagesize());
#endif
}

inline void * reserve(std::size_t bytes) noexcept {
#if defined(_WIN32)
  return VirtualAlloc2(nullptr, nullptr, bytes, MEM_RESERVE | MEM_RESERVE_PLACEHOLDER,
                       PAGE_NOACCESS, nullptr, 0);
#else
  auto * result = mmap(nullptr, bytes, PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
  return result == MAP_FAILED ? nullptr : result;
#endif
}

inline bool release(void * base, std::size_t bytes) noexcept {
#if defined(_WIN32)
  MEMORY_BASIC_INFORMATION info{};
  if (!VirtualQuery(base, &info, sizeof(info))) return false;
  if (info.RegionSize != bytes &&
      !VirtualFree(base, bytes, MEM_RELEASE | MEM_COALESCE_PLACEHOLDERS)) return false;
  return VirtualFree(base, 0, MEM_RELEASE) != 0;
#else
  return munmap(base, bytes) == 0;
#endif
}
}
