-- SPDX-FileCopyrightText: 2026 Edward Kmett <ekmett@gmail.com>
-- SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0
import Std

namespace Jam.Finalizers.Closure

structure Association where
  key : Nat
  value : Nat
  finalizer : Nat
  deriving DecidableEq, Repr

def Field (a : Association) (x : Nat) : Prop :=
  x ≠ 0 ∧ (x = a.value ∨ x = a.finalizer)

inductive Strong (edge : Nat → Nat → Prop) (roots : Nat → Prop) : Nat → Prop where
  | root : roots x → Strong edge roots x
  | follow : Strong edge roots x → edge x y → Strong edge roots y

inductive Live (roots : Nat → Prop) (edge : Nat → Nat → Prop)
    (registry : List Association) : Nat → Prop where
  | root : roots x → Live roots edge registry x
  | strong : Live roots edge registry x → edge x y → Live roots edge registry y
  | conditional : a ∈ registry → Live roots edge registry a.key → Field a x →
      Live roots edge registry x

def Closed (roots : Nat → Prop) (edge : Nat → Nat → Prop)
    (registry : List Association) (m : Nat → Prop) : Prop :=
  (∀ x, roots x → m x) ∧
  (∀ x y, m x → edge x y → m y) ∧
  (∀ a, a ∈ registry → m a.key → ∀ x, Field a x → m x)

theorem live_least {roots edge registry m} (closed : Closed roots edge registry m) :
    ∀ x, Live roots edge registry x → m x := by
  intro x h
  induction h with
  | root h => exact closed.1 _ h
  | strong _ edge ih => exact closed.2.1 _ _ ih edge
  | conditional member _ field ih => exact closed.2.2 _ member ih _ field

theorem live_closed (roots edge registry) : Closed roots edge registry (Live roots edge registry) :=
  ⟨fun _ => Live.root, fun _ _ => Live.strong, fun _ ha hk _ hf => Live.conditional ha hk hf⟩

theorem strong_mono {edge a b} (h : ∀ x, a x → b x) :
    ∀ x, Strong edge a x → Strong edge b x := by
  intro x hx
  induction hx with
  | root hx => exact .root (h _ hx)
  | follow _ he ih => exact .follow ih he

theorem strong_into {edge : Nat → Nat → Prop} {roots m : Nat → Prop} (hr : ∀ x, roots x → m x)
    (he : ∀ x y, m x → edge x y → m y) : ∀ x, Strong edge roots x → m x := by
  intro x hx
  induction hx with
  | root hx => exact hr _ hx
  | follow _ h ih => exact he _ _ ih h

structure State where
  marked : Nat → Prop
  activated : Association → Prop

def selected (registry : List Association) (s : State) (a : Association) : Prop :=
  a ∈ registry ∧ ¬s.activated a ∧ s.marked a.key

/-- One full `close` scan followed by a synchronous drained trace. Identical
triples may share an activation bit in this abstraction; lifecycle/token proofs
retain separate registration incarnations. -/
def round (edge : Nat → Nat → Prop) (registry : List Association) (s : State) : State :=
  ⟨Strong edge (fun x => s.marked x ∨ ∃ a, selected registry s a ∧ Field a x),
   fun a => s.activated a ∨ selected registry s a⟩

def initial (roots : Nat → Prop) (edge : Nat → Nat → Prop) : State := ⟨Strong edge roots, fun _ => False⟩

def iterate (roots : Nat → Prop) (edge : Nat → Nat → Prop) (registry : List Association) : Nat → State
  | 0 => initial roots edge
  | n + 1 => round edge registry (iterate roots edge registry n)

def Invariant (roots : Nat → Prop) (edge : Nat → Nat → Prop) (registry : List Association) (s : State) : Prop :=
  (∀ x, s.marked x → Live roots edge registry x) ∧
  (∀ x, roots x → s.marked x) ∧
  (∀ x y, s.marked x → edge x y → s.marked y) ∧
  (∀ a, s.activated a → ∀ x, Field a x → s.marked x)

theorem initial_invariant (roots edge registry) :
    Invariant roots edge registry (initial roots edge) := by
  refine ⟨?_, ?_, ?_, ?_⟩
  · exact strong_into (fun _ => Live.root) (fun _ _ => Live.strong)
  · exact fun _ => Strong.root
  · exact fun _ _ => Strong.follow
  · intro a ha; exact False.elim ha

theorem round_invariant {roots edge registry s} (h : Invariant roots edge registry s) :
    Invariant roots edge registry (round edge registry s) := by
  refine ⟨?_, ?_, ?_, ?_⟩
  · change ∀ x, Strong edge _ x → Live roots edge registry x
    apply strong_into (m := Live roots edge registry) ?_ (fun _ _ hx he => .strong hx he)
    intro x hx
    rcases hx with hx | ⟨a, ⟨member, _, key⟩, field⟩
    · exact h.1 x hx
    · exact .conditional member (h.1 _ key) field
  · intro x hx; exact .root (.inl (h.2.1 x hx))
  · exact fun _ _ => Strong.follow
  · intro a ha x hf
    rcases ha with old | new
    · exact .root (.inl (h.2.2.2 a old x hf))
    · exact .root (.inr ⟨a, new, hf⟩)

theorem iterate_invariant (roots edge registry n) :
    Invariant roots edge registry (iterate roots edge registry n) := by
  induction n with
  | zero => exact initial_invariant _ _ _
  | succ n ih => exact round_invariant ih

/-- A scan with no selected association is exactly the implementation's empty
scratch-buffer exit. The marked set at that exit is the least fixed point. -/
theorem stopped_is_least (roots edge registry n)
    (stopped : ∀ a, ¬selected registry (iterate roots edge registry n) a) :
    ∀ x, (iterate roots edge registry n).marked x ↔ Live roots edge registry x := by
  have inv := iterate_invariant roots edge registry n
  intro x
  refine ⟨inv.1 x, live_least ⟨inv.2.1, inv.2.2.1, ?_⟩ x⟩
  intro a member key y field
  have activated : (iterate roots edge registry n).activated a := by
    apply Classical.byContradiction
    intro h
    exact stopped a ⟨member, h, key⟩
  exact inv.2.2.2 a activated y field

theorem registry_membership_only {roots edge left right}
    (same : ∀ a, a ∈ left ↔ a ∈ right) :
    ∀ x, Live roots edge left x ↔ Live roots edge right x := by
  have direction : ∀ l r : List Association, (∀ a, a ∈ l → a ∈ r) →
      ∀ x, Live roots edge l x → Live roots edge r x := by
    intro l r sub
    apply live_least
    exact ⟨fun _ => Live.root, fun _ _ => Live.strong,
      fun a member key _ field => .conditional (sub a member) key field⟩
  intro x
  exact ⟨direction left right (fun a => (same a).mp) x,
    direction right left (fun a => (same a).mpr) x⟩

theorem registration_order_independent {roots edge left right n m}
    (same : ∀ a, a ∈ left ↔ a ∈ right)
    (hl : ∀ a, ¬selected left (iterate roots edge left n) a)
    (hr : ∀ a, ¬selected right (iterate roots edge right m) a) :
    ∀ x, (iterate roots edge left n).marked x ↔ (iterate roots edge right m).marked x := by
  intro x
  exact (stopped_is_least roots edge left n hl x).trans
    ((registry_membership_only same x).trans (stopped_is_least roots edge right m hr x).symm)

/-- No conditional cycle bootstraps itself when the graph has no root. -/
theorem unrooted_cycles_die (edge registry x) : ¬Live (fun _ => False) edge registry x := by
  exact live_least ⟨fun _ h => h, fun _ _ h _ => h, fun _ _ h _ _ => h⟩ x

/-- More generally, any set excluding roots and closed against incoming strong
and conditional edges stays dead, regardless of its internal cycles. -/
theorem unreachable_region {roots edge registry} (region : Nat → Prop)
    (h : Closed roots edge registry (fun x => ¬region x)) :
    ∀ x, region x → ¬Live roots edge registry x := by
  intro x inside live
  exact live_least h x live inside

/-- Old-key conservatism is represented as extra initial roots; it can retain
more objects, but never removes major-collection reachability. -/
theorem conservative_minor {roots edge registry} (old : Nat → Prop) :
    ∀ x, Live roots edge registry x → Live (fun x => roots x ∨ old x) edge registry x := by
  apply live_least
  exact ⟨fun _ h => .root (.inl h), fun _ _ => Live.strong,
    fun _ ha hk _ hf => Live.conditional ha hk hf⟩

theorem old_key_retains_fields {roots : Nat → Prop} {edge : Nat → Nat → Prop} {registry : List Association} (old : Nat → Prop)
    (a : Association) (ha : a ∈ registry) (hk : old a.key) (x : Nat) (hf : Field a x) :
    Live (fun x => roots x ∨ old x) edge registry x :=
  .conditional ha (.root (.inr hk)) hf

end Jam.Finalizers.Closure
