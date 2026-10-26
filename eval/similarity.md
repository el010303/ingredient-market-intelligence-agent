### Similarity of labeled pairs by grade

| Grade | Pairs | Min | 10th pct | Median | 90th pct | Max |
|---|---|---|---|---|---|---|
| 2 | 80 | 0.43 | 0.48 | 0.64 | 0.76 | 0.80 |
| 1 | 16 | 0.38 | 0.50 | 0.63 | 0.69 | 0.72 |
| 0 | 68 | 0.32 | 0.35 | 0.46 | 0.56 | 0.60 |

### What a gate at each threshold would do

| Threshold | Relevant (grade 2) kept | Junk (grade 0) removed |
|---|---|---|
| 0.30 (before tuning) | 100% | 0% |
| 0.35 | 100% | 10% |
| 0.40 | 100% | 21% |
| 0.45 **(production)** | 95% | 44% |
| 0.50 | 85% | 69% |
| 0.55 | 70% | 84% |
| 0.60 | 57% | 99% |
