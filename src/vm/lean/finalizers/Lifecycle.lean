-- SPDX-FileCopyrightText: 2026 Edward Kmett <ekmett@gmail.com>
-- SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0
import Std

namespace Jam.Finalizers

inductive Phase where
  | active | queued | running | dead
  deriving DecidableEq, Repr

structure Entry where
  phase : Phase
  key : Nat
  value : Nat
  finalizer : Nat
  deriving DecidableEq, Repr

inductive Command where
  | observe
  | freeze (keyLive : Bool)
  | take
  | finalize
  | complete
  deriving DecidableEq, Repr

def cleared : Entry := ⟨.dead, 0, 0, 0⟩

def retire (e : Entry) (claim : Bool) : Entry × Nat :=
  if e.finalizer = 0 then (cleared, 0)
  else (⟨if claim then .running else .queued, 0, 0, e.finalizer⟩,
        if claim then 1 else 0)

/-- A serialized operation on one registration incarnation. `take` selects this
entry only when queued; failed selection and begin/roots are observations. -/
def step (c : Command) (e : Entry) : Entry × Nat :=
  match c, e.phase with
  | .freeze false, .active => retire e false
  | .take, .queued => ({e with phase := .running}, 1)
  | .finalize, .active | .finalize, .queued => retire e true
  | .complete, .running => (cleared, 0)
  | _, _ => (e, 0)

def credit : Phase → Nat
  | .active | .queued => 1
  | .running | .dead => 0

def deref (e : Entry) : Nat := if e.phase = .active then e.value else 0
def root (e : Entry) : Nat :=
  if e.phase = .queued ∨ e.phase = .running then e.finalizer else 0

def execute : List Command → Entry → Entry × Nat
  | [], e => (e, 0)
  | c :: cs, e =>
      let next := step c e
      let rest := execute cs next.1
      (rest.1, next.2 + rest.2)

theorem step_budget (c : Command) (e : Entry) :
    (step c e).2 + credit (step c e).1.phase ≤ credit e.phase := by
  rcases e with ⟨p, k, v, f⟩
  cases c with
  | freeze b =>
    cases b <;> cases p <;> by_cases hf : f = 0 <;>
      simp [step, retire, cleared, credit, hf]
  | _ =>
    cases p <;> by_cases hf : f = 0 <;>
      simp [step, retire, cleared, credit, hf]

theorem execute_budget (cs : List Command) (e : Entry) :
    (execute cs e).2 + credit (execute cs e).1.phase ≤ credit e.phase := by
  induction cs generalizing e with
  | nil => simp [execute]
  | cons c cs ih =>
    have h := step_budget c e
    have h' := ih (step c e).1
    simp only [execute] at *
    omega

/-- Counts successful claims, not callback invocations by an arbitrary client. -/
theorem at_most_one_claim (cs : List Command) (e : Entry) :
    (execute cs e).2 ≤ 1 := by
  have h := execute_budget cs e
  have hc : credit e.phase ≤ 1 := by cases e.phase <;> simp [credit]
  omega

theorem step_no_rearm (c : Command) (e : Entry) (h : e.phase ≠ .active) :
    (step c e).1.phase ≠ .active := by
  rcases e with ⟨p, k, v, f⟩
  cases c <;> cases p <;> simp_all [step, retire, cleared] <;>
    split <;> simp_all

theorem execute_no_rearm (cs : List Command) (e : Entry) (h : e.phase ≠ .active) :
    (execute cs e).1.phase ≠ .active := by
  induction cs generalizing e with
  | nil => exact h
  | cons c cs ih => exact ih _ (step_no_rearm c e h)

theorem retired_deref_null (cs : List Command) (e : Entry) (h : e.phase ≠ .active) :
    deref (execute cs e).1 = 0 := by
  simp [deref, execute_no_rearm cs e h]

/-- GC observations may repeat arbitrarily; only explicit completion releases
a queued/running root. Client execution and collection progress are not assumed. -/
theorem pending_root_step (c : Command) (e : Entry)
    (h : e.phase = .queued ∨ e.phase = .running) (hf : e.finalizer ≠ 0)
    (hc : c ≠ .complete) :
    ((step c e).1.phase = .queued ∨ (step c e).1.phase = .running) ∧
    (step c e).1.finalizer = e.finalizer := by
  rcases e with ⟨p, k, v, f⟩
  cases c <;> cases p <;> simp_all [step, retire]

theorem pending_root_until_complete (cs : List Command) (e : Entry)
    (h : e.phase = .queued ∨ e.phase = .running) (hf : e.finalizer ≠ 0)
    (hc : Command.complete ∉ cs) :
    root (execute cs e).1 = e.finalizer := by
  induction cs generalizing e with
  | nil => simp [execute, root, h]
  | cons c cs ih =>
    have hn : c ≠ .complete := by intro he; apply hc; simp [he]
    have ht : Command.complete ∉ cs := by intro he; exact hc (List.mem_cons_of_mem c he)
    obtain ⟨hp, he⟩ := pending_root_step c e h hf hn
    have hf' : (step c e).1.finalizer ≠ 0 := by simpa [he] using hf
    simpa [execute, he] using ih (step c e).1 hp hf' ht

theorem complete_releases_running (e : Entry) (h : e.phase = .running) :
    root (step .complete e).1 = 0 ∧ (step .complete e).1 = cleared := by
  simp [step, h, root, cleared]

/-- A nonterminating callback can remain rooted for arbitrarily many GC cycles. -/
theorem hung_callback_stays_running (n : Nat) (k v f : Nat) :
    (execute (List.replicate n (.freeze false)) ⟨.running, k, v, f⟩).1 =
      ⟨.running, k, v, f⟩ := by
  induction n with
  | zero => rfl
  | succ n ih => simpa [List.replicate_succ, execute, step] using ih

/-- A closure can refer back to a retired key without reactivating its entry. -/
theorem resurrection_does_not_rearm (e : Entry) (cs : List Command)
    (h : e.phase ≠ .active) :
    deref (execute (.freeze true :: cs) e).1 = 0 :=
  retired_deref_null _ _ h

end Jam.Finalizers
