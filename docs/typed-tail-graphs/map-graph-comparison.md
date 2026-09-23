# Typed AST graph comparison

Latest compilations matched by root ID and label. Counts are static graph sites.

Current Map module hashes match the published v3 modules: **True**.

| Root | Compilation old/new | PE | Before high | After mid | Guest calls | Object[] | Long boxes | Inlined edges |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| insert | 2584 / 2621 | 1365 → 1444 | 4466 → 4322 | 10225 → 10060 | 21 → 21 | 18 → 18 | 18 → 18 | 9 → 9 |
| adjust | 2698 / 2749 | 519 → 494 | 2495 → 2403 | 5789 → 5560 | 8 → 8 | 8 → 8 | 8 → 8 | 6 → 6 |
| lookup | 2757 / 2811 | 556 → 574 | 389 → 376 | 753 → 797 | 0 → 0 | 0 → 0 | 0 → 0 | 1 → 1 |
| weighted fold | 2792 / 2846 | 495 → 463 | 5596 → 5601 | 11963 → 12527 | 16 → 17 | 16 → 17 | 19 → 18 | 17 → 18 |
| range 36 | 2804 / 2857 | 560 → 486 | 3521 → 3609 | 8598 → 8937 | 18 → 17 | 15 → 14 | 17 → 16 | 8 → 9 |
| balance 10 | 2833 / 2888 | 788 → 770 | 451 → 411 | 1127 → 1006 | 0 → 0 | 0 → 0 | 0 → 0 | 0 → 0 |
| query | 2820 / 2872 | 544 → 413 | 1106 → 1250 | 2725 → 3371 | 0 → 0 | 1 → 0 | 1 → 1 | 5 → 7 |
| range 31 | 2824 / 2874 | 585 → 511 | 5813 → 5616 | 12742 → 12898 | 19 → 21 | 17 → 18 | 17 → 20 | 19 → 21 |
| balance 15 | 2837 / 2893 | 1387 → 1362 | 5767 → 5885 | 12964 → 13085 | 21 → 21 | 18 → 18 | 18 → 18 | 11 → 11 |
| entry | 2881 / 2930 | 121 → 82 | 229 → 184 | 511 → 426 | 1 → 1 | 1 → 1 | 1 → 1 | 2 → 2 |

## Residual Java calls

* insert: {} → {}
* adjust: {} → {}
* lookup: {} → {}
* weighted fold: {'java.lang.Long.longValue()': 3} → {}
* range 36: {} → {}
* balance 10: {} → {}
* query: {} → {}
* range 31: {'java.lang.Long.longValue()': 1} → {}
* balance 15: {} → {}
* entry: {} → {}

## Interpretation limits

More inlining can expose additional branching call sites, so residual static counts alone do not measure dynamic calls. Long boxes at a surviving Object call ABI may remain necessary. Check source origins before attributing any removed site to typed Case, Let, constructor fields, or root-result forwarding.
