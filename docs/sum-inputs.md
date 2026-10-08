# Typed unboxed sum inputs and captures

Both backends accept ordinary guest sums with two or more alternatives through
direct and higher-order calls, partial applications, overapplication and tail transfers.
The supported payloads are the existing [sum result layouts](sum-results.md):
lowered integral carriers (including Int64/Word64), Float, Double, known
references, void and tuples of these. One sum is one logical argument,
independently of its physical width. Exact
alternatives and projections distinguish sums sharing the same physical fields.

Genuine GHC pre/post export of ordinary and local-join sum inputs, PAPs, tail
calls, overapplication and escaped captures remains unqualified. Fixture-free
owners cover the runtime transport and lifetime contracts.

The existing typed input packet carries the tag and concrete payload fields.
Entry copies them into callee locals and releases the incoming loan before guest
code continues. Durable PAP prefixes own separate fields; they never retain a
pooled input or a caller frame. Overapplication advances by logical argument
count, then uses the physical offsets appropriate to the next function.

Closure and thunk captures flatten a whole sum into ordinary exact capture
properties and restore a logical sum alias in the new frame. Long, Float and
Double remain primitive properties. Lifted leaves remain lazy, including a
bottom in an ignored alternative field. Inactive reference slots remain null
padding and do not appear as pointers in closure inspection. Constructors clear
inactive fields before publishing the tag; captures and PAPs copy that state.
There is no new boxed sum value or payload array.

Local joins accept the same exact sum layouts and capture enclosing sums through
their existing typed frame slots. A transfer first evaluates every operand into
scratch slots, then moves all tag/payload fields in parallel; recursive swaps
therefore preserve both operands. Completed transfers clear scratch references.
A closure escaping a join owns its captured sum fields independently of that
activation. Logical alternatives remain checked even when physical layouts match.

Tuples may recursively contain supported sums; physical tag/payload slots expand
at the sum's logical position without losing surrounding tuple boundaries or
zero-width components. Concrete nested sums, evaluated managed addresses and
supported exact vector species use the same derived typed storage as results;
the original GHC physical proof remains unchanged. Nonrecursive unlifted sum lets
evaluate once into typed frame locals. Unresolved sum payloads, recursive/lifted
sum lets and global aggregate storage remain excluded. The
[Core host ABI](site/embedding.md#load-a-core-entry) transports supported sums
as `[tag, payload]` arrays with a 1-based tag. Unsaturated sum constructors
remain excluded.
