# Triage Labels

The skills speak in terms of five canonical triage roles. This repository tracks issues as local Markdown (see `issue-tracker.md`), so a "label" is the value on the file's `Status:` line. This file maps those roles to the strings used here.

| Label in mattpocock/skills | Label in our tracker | Meaning                                  |
| -------------------------- | -------------------- | ---------------------------------------- |
| `needs-triage`             | `needs-triage`       | Maintainer needs to evaluate this issue  |
| `needs-info`               | `needs-info`         | Waiting on reporter for more information |
| `ready-for-agent`          | `ready-for-agent`    | Fully specified, ready for an AFK agent  |
| `ready-for-human`          | `ready-for-human`    | Requires human implementation            |
| `wontfix`                  | `wontfix`            | Will not be actioned                     |

Finished work keeps the existing `Status: resolved`.

When a skill mentions a role (e.g. "apply the AFK-ready triage label"), set the `Status:` line to the corresponding string from this table.
