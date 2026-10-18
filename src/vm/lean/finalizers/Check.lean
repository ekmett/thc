-- SPDX-FileCopyrightText: 2026 Edward Kmett <ekmett@gmail.com>
-- SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0
import Batch
import Tokens

#print axioms Jam.Finalizers.at_most_one_claim
#print axioms Jam.Finalizers.execute_budget
#print axioms Jam.Finalizers.retired_deref_null
#print axioms Jam.Finalizers.pending_root_until_complete
#print axioms Jam.Finalizers.complete_releases_running
#print axioms Jam.Finalizers.hung_callback_stays_running
#print axioms Jam.Finalizers.resurrection_does_not_rearm
#print axioms Jam.Finalizers.Closure.iterate_invariant
#print axioms Jam.Finalizers.Closure.stopped_is_least
#print axioms Jam.Finalizers.Closure.registration_order_independent
#print axioms Jam.Finalizers.Closure.unrooted_cycles_die
#print axioms Jam.Finalizers.Closure.unreachable_region
#print axioms Jam.Finalizers.Closure.conservative_minor
#print axioms Jam.Finalizers.Closure.old_key_retains_fields
#print axioms Jam.Finalizers.Tokens.token_injective
#print axioms Jam.Finalizers.Tokens.token_positive_signed
#print axioms Jam.Finalizers.Tokens.reuse_increases
#print axioms Jam.Finalizers.Tokens.exhausted_slot_not_reused
#print axioms Jam.Finalizers.Tokens.stale_completion_preserves_occupant
#print axioms Jam.Finalizers.Tokens.high_bit_rejected
#print axioms Jam.Finalizers.batch_order_independent
#print axioms Jam.Finalizers.late_key_rescue_cannot_revive
#print axioms Jam.Finalizers.early_trace_changes_second_decision
#print axioms Jam.Finalizers.finalizer_backedge_rescues_object
#print axioms Jam.Finalizers.premature_complete_releases_root
