## Fast checks

Revision: `7e0cacadeb0a32a638355401b0b64fdc4a6a1bc0`

Elapsed since checkout: 69.8s (includes setup/cache steps).
Native inputs: verified-hit.
Scope: narrow.

| Phase | Seconds | Exit |
| --- | ---: | ---: |
| input-identity | 1.720 | 0 |
| select | 1.888 | 0 |
| native-restore | 5.061 | 0 |
| python-000 | 4.898 | 0 |
| python-001 | 4.838 | 0 |
| python-002 | 0.048 | 0 |
| python-003 | 0.143 | 0 |
| junit-default | 23.969 | 0 |
| junit-dense | 27.015 | 0 |

Warm ordinary PRs target 2–3 minutes; cold/bootstrap and widened checks may take longer.
