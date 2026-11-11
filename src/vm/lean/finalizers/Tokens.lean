-- SPDX-FileCopyrightText: 2026 Edward Kmett <ekmett@gmail.com>
-- SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0
import Std

namespace Jam.Finalizers.Tokens

def base : Nat := 4294967296
def lastGeneration : Nat := 2147483647
def token (slot generation : Nat) : Nat := generation * base + slot + 1

theorem token_low (slot generation : Nat) (hs : slot < base - 1) :
    token slot generation % base = slot + 1 := by
  simp [token, base] at *
  omega

theorem token_high (slot generation : Nat) (hs : slot < base - 1) :
    token slot generation / base = generation := by
  simp [token, base] at *
  omega

theorem token_positive_signed (slot generation : Nat)
    (hs : slot < base - 1) (hg : generation ≤ lastGeneration) :
    0 < token slot generation ∧ token slot generation < 9223372036854775808 := by
  simp [token, base, lastGeneration] at *
  omega

theorem token_injective (s t g h : Nat) (hs : s < base - 1) (ht : t < base - 1)
    (equal : token s g = token t h) : s = t ∧ g = h := by
  have low := congrArg (fun n => n % base) equal
  have high := congrArg (fun n => n / base) equal
  dsimp at low high
  rw [token_low s g hs, token_low t h ht] at low
  rw [token_high s g hs, token_high t h ht] at high
  omega

/-- Removing a maximum-generation entry retires its slot permanently. -/
def recycle (generation : Nat) : Option Nat :=
  if generation = lastGeneration then none else some (generation + 1)

theorem recycle_strict {g next : Nat} (bound : g ≤ lastGeneration)
    (h : recycle g = some next) : g < next ∧ next ≤ lastGeneration := by
  unfold recycle at h
  split at h <;> simp_all <;> omega

theorem exhausted_slot_not_reused : recycle lastGeneration = none := by
  simp [recycle]

inductive Reused : Nat → Nat → Prop where
  | one : recycle g = some h → Reused g h
  | more : Reused g h → recycle h = some j → Reused g j

theorem reuse_increases {g h : Nat} (bound : g ≤ lastGeneration)
    (steps : Reused g h) : g < h ∧ h ≤ lastGeneration := by
  induction steps with
  | one h => exact recycle_strict bound h
  | more _ h ih =>
    obtain ⟨lt, le⟩ := ih
    obtain ⟨lt', le'⟩ := recycle_strict le h
    omega

/-- `find` after excluding dead entries and out-of-range slots. Low word zero
cannot designate an allocated slot: its C++ unsigned subtraction gives none. -/
def Matches (id slot generation : Nat) : Prop :=
  id % base = slot + 1 ∧ id / base = generation

theorem own_token_matches (slot generation : Nat) (hs : slot < base - 1) :
    Matches (token slot generation) slot generation :=
  ⟨token_low slot generation hs, token_high slot generation hs⟩

theorem zero_token_rejected (slot generation : Nat) : ¬Matches 0 slot generation := by
  simp [Matches, base]

theorem stale_token_rejected {slot g h : Nat}
    (hs : slot < base - 1) (hg : g ≤ lastGeneration) (reuse : Reused g h) :
    ¬Matches (token slot g) slot h := by
  have greater := reuse_increases hg reuse
  intro matched
  have equal := matched.2
  rw [token_high slot g hs] at equal
  omega

theorem other_slot_rejected (s t g h : Nat) (hs : s < base - 1) (different : s ≠ t) :
    ¬Matches (token s g) t h := by
  intro matched
  have equal := matched.1
  rw [token_low s g hs] at equal
  omega

theorem high_bit_rejected {id slot generation : Nat}
    (high : 9223372036854775808 ≤ id) (bound : generation ≤ lastGeneration) :
    ¬Matches id slot generation := by
  intro matched
  have h := matched.2
  simp [base, lastGeneration] at *
  omega

/-- A rejected lookup makes a stale completion a no-op for the current occupant. -/
def completeIfMatches (id slot generation : Nat) (running : Bool) : Bool :=
  if id % base = slot + 1 ∧ id / base = generation then false else running

theorem stale_completion_preserves_occupant {slot g h : Nat} (running : Bool)
    (hs : slot < base - 1) (hg : g ≤ lastGeneration) (reuse : Reused g h) :
    completeIfMatches (token slot g) slot h running = running := by
  have h := stale_token_rejected hs hg reuse
  unfold completeIfMatches
  exact if_neg h

end Jam.Finalizers.Tokens
