# Independent review scope

Two independent workers reviewed this slice without executing guests or changing
the frozen captures. The primary worker also checked the retained artifact hashes,
compressed graph/Core copies and actual full-suite results.

The xhigh reviewer checked runtime lane storage, both sixteen-lane unpack paths,
exact Word8 versus Int8 representation/literal proofs, compiled-entry/target/pool
checks, build inputs and CI wiring. Its initial saved-report audit parsed all 97
default XMLs and all 467 testcase elements, checked duplicate identities and the
matching 467 PASSED log lines, and verified all 17 native source plus 7 artifact
hashes. It independently derived 61,696 compiled calls and 64,512 guest entries
per mode from the actual corpus and closure counts. It reviewed the retained
initial focused failure and test-only diagnostic expectation correction: default
rejection, exact deferred reason, on-demand trap, one trap count, and pool release
all remain required. No acceptance or runtime-policy relaxation was introduced.

Its final archive review verified all 194 saved full XMLs against the two archives
byte-for-byte, including every manifest hash and XML attribute. Each mode has 97
unique suites, 467 unique testcase identities, zero failures/errors/skips, and the
same testcase set. Both logs contain 467 PASSED lines; handoff prints the selected
flag in both Gradle and test JVM contexts and executes all twelve tasks, including
test and installDist. Focused XML/log copies preserve exactly six initial passes
and one failure, followed by seven corrected passes. It also reverified frozen
source blobs, native provenance, runtime/JDK hashes, all 84 capture hash references
and retained compressed graph/Core, final-LIR, log and status copies. The final
README and SHA256SUMS were generated afterward by the primary worker and are not
part of that independent sign-off.

The ultra reviewer independently reconstructed all 7,712 native/model rows from
the Haskell lane algebra, unsigned reduction and checksum weights. It checked
signed machine overflow, all eighty operation/lane boundary grids, 2,928 high-bit
lane observations, actual pre/post Core closure membership, residual helpers,
sixteen Word8 tuple leaves and all four labeled signedness mutation reports.

Its frozen-capture audit verified all 84 per-record raw/parsed/LIR/log/command/
status hashes, graph selection and compilation identities, and zero run/parse/
check statuses. All 69 frozen source hashes also match the committed source blobs;
11 runtime JARs, four JDK files and 24 native provenance hashes match. Independent
traversal from the actual Return nodes proves all 192 lane cuts feed exact
ZeroExtend 8-to-64-bit conversions. Four multiplication graphs have the exact
mask-255/shift-8/two-product reconstruction. Final LIR has four XMM VPADDB, four
XMM VPSUBB and eight XMM VPMULLW. Six virtual byte arrays are proven to be only
FrameWithoutBoxing.indexedTags deoptimization metadata. All other payload,
allocation, field, call and lane-box gates pass.

All twelve runs verify 176 native answers twice after compilation: 4,224
comparisons with unchanged active targets and valid installed last-tier entries.
The graph checker passed first time: no correction or guest replay. Thirty-four
parser mutation tests pass normally and under Python -O. Evidence SHA256 is
`4c6d5de32b1af2ca90d264bd1894e3d244eed196b0a3ae9fb68fe10cbff82345`.

These are bounded correctness, representation, compiled-execution and packed-x86
reviews. They do not establish throughput, a globally allocation-free ABI,
no spills, vector calling conventions or execution on other architectures.
