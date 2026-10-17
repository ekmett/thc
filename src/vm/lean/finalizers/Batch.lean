-- SPDX-FileCopyrightText: 2026 Edward Kmett <ekmett@gmail.com>
-- SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0
import Lifecycle
import Closure

namespace Jam.Finalizers

/-- All decisions use the same frozen marking result. Dead entries remain as
tombstones here; the C++ dense live vector removes them by swapping its tail. -/
def freezeBatch (live : Nat → Bool) (entries : List Entry) : List Entry :=
  entries.map (fun e => (step (.freeze (live e.key)) e).1)

theorem batch_order_independent (live : Nat → Bool) (left right : List Entry)
    (h : left.Perm right) : (freezeBatch live left).Perm (freezeBatch live right) :=
  h.map _

theorem frozen_dead_not_active (e : Entry) (h : e.phase = .active) :
    (step (.freeze false) e).1.phase ≠ .active := by
  rcases e with ⟨p, k, v, f⟩
  simp_all [step, retire, cleared]
  split <;> simp

/-- Tracing a newly dead finalizer may subsequently make its key live. It still
cannot change the frozen registration's death decision. -/
theorem late_key_rescue_cannot_revive (e : Entry) (h : e.phase = .active)
    (later : List Command) :
    deref (execute later (step (.freeze false) e).1).1 = 0 :=
  retired_deref_null _ _ (frozen_dead_not_active e h)

theorem no_finalizer_retirement_is_dead (k v : Nat) :
    (step (.freeze false) ⟨.active, k, v, 0⟩).1 = cleared := by
  simp [step, retire]

/-- Two registrations sharing an unrooted key both retire. If tracing the first
finalizer rescues key 1 before deciding the second, the second incorrectly stays
active relative to the VM batch policy. This is an explicit alternative policy,
not a counterexample to the current VM implementation. -/
theorem early_trace_changes_second_decision :
    freezeBatch (fun _ => false) [⟨.active, 1, 2, 3⟩, ⟨.active, 1, 4, 0⟩] =
      [⟨.queued, 0, 0, 3⟩, cleared] ∧
    (step (.freeze true) ⟨.active, 1, 4, 0⟩).1.phase = .active := by
  decide

/-- The backedge used above: before finalizer 3 is rooted, the key is dead;
rooting finalizer 3 makes key 1 strongly live, without changing retired entries. -/
theorem finalizer_backedge_rescues_object :
    (¬Closure.Live (fun _ => False) (fun x y => x = 3 ∧ y = 1)
      [⟨1, 2, 3⟩, ⟨1, 4, 0⟩] 1) ∧
    Closure.Strong (fun x y => x = 3 ∧ y = 1) (fun x => x = 3) 1 := by
  exact ⟨Closure.unrooted_cycles_die _ _ _, .follow (.root rfl) ⟨rfl, rfl⟩⟩

/-- `complete` is a client obligation, not evidence that the callback ran.
An asynchronous consumer completing immediately has already released the root. -/
theorem premature_complete_releases_root (k v f : Nat) (hf : f ≠ 0) :
    root (execute [.finalize, .complete] ⟨.active, k, v, f⟩).1 = 0 ∧
    (execute [.finalize, .complete] ⟨.active, k, v, f⟩).2 = 1 := by
  simp [execute, step, retire, hf, cleared, root]

end Jam.Finalizers
