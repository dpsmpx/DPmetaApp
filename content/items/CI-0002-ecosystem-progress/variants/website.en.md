---
schema: variant/v1
item: CI-0002
platform: website
lang: en
format: page
source_version: 4
version: 3
status: ready
trust: validated
title: Ecosystem progress
link: null
generation:
  provider: session
  note: 'английский вариант канона v4 (Д21, Д22): тот же смысл, формулировки фактов на en; фигуры набора
    2026-10-09'
summary: From 11 September to 9 October 2026 one game grew into an ecosystem of repositories — the core,
  control and a support layer. This page shows what was built — from git history measurements and the
  projects' own documents.
deps:
  facts:
    GH-NAME: 18e9e8bc34f1c9f0
    GH-GENRE: 87840cd38160c9f1
    DP-WHAT: 091c2d23907bb1fb
    VRF-WHAT: 917a8dae29da7eb5
    SMI-WHAT: cbf16ce5b6f07403
    PRF-WHAT: 774e3cfe896d3a27
    LRN-WHAT: bab936f8c2f8776c
    MKT-WHAT: d7cee6de6f534b61
    META-WHAT: d86fda377e1113a5
    ORC-WHAT: 032d9f19e4ee9a1b
    VIS-WHAT: be359c822796fc5c
    MON-WHAT: 58d9b24a09124db2
    APP-WHAT: e0a79055d2da5359
    GH-STAGES: 9967754b7d9b5500
    DP-STAGE: 0d8aced210be861e
    VRF-STAGE: 1e4e75cc2d4739a1
    SMI-SITE: 2b6e2da58984e103
    PRF-STAGE: 40947e9899f1a802
    LRN-STAGE: 374e468812f9a5f2
    MKT-STAGE: 85a31655a6f4615e
    META-STAGE: 812717a1ab0bcfea
    ORC-STAGE: a2bf3e66a0bd346a
    META-NO-DEPS: 538aa32a19a5c5ea
  links: {}
  releases: {}
  datasets:
    '2026-10-09': 5ddfa669663ccbbb
  canonical:
    version: 4
    sha256: 917b104b4e5e63239c08c33871fe5ca9d284d4921d8cb1ba59f6ef31fa19d214
  media: {}
---
The period runs from {{progress:2026-10-09.period_start}} to {{progress:2026-10-09.period_end}}, from the first commit of {{fact:GH-NAME#1}} to the snapshot at {{progress:2026-10-09.cutoff}}. That is {{progress:2026-10-09.span_days}} days; the charts show {{progress:2026-10-09.days}} calendar dates, and the last one is partial.

Only the current system is counted — repositories started together with the game repository and after it, except those the author removed. Earlier projects and the removed ones form an archive: they are neither shown nor counted here.

The numbers here are measurements of git history and of the projects' own documents; derived values follow the formulas in "How it was measured". A commit count is not a measure of value, so progress is shown through several measurements at once: work per day, branches, code, checks, decisions, structure.


## Snapshot on 9 October

Where the projects stand now. Stages come from the projects' own documents, with a date. There are no readiness percentages: the projects do not define them.

{{figure:2026-10-09.snapshot}}

## Every day

Over the period — {{progress:2026-10-09.commits}} commits by the author and Claude across all branches and {{progress:2026-10-09.merges}} merged pull requests; there were commits on {{progress:2026-10-09.active_days}} of {{progress:2026-10-09.days}} days. Until 3 October Galdhold was the only repository in the count. From 3 October new core repositories appear, from 5 October control and the support layer; {{progress:2026-10-09.peak_day}} is the peak: {{progress:2026-10-09.peak_commits}} commits and {{progress:2026-10-09.peak_merges}} pull requests.

The columns are split into three groups — Galdhold, the rest of the core, control and the support layer — so that work outside Galdhold does not disappear against it: Galdhold accounts for {{progress:2026-10-09.galdhold_share}} of all commits. A commit is a unit of history, not a measure of quality: one commit can be a line in a document, another a new system.

{{figure:2026-10-09.activity}}

## Accumulation

How much has been done since the start of the period. Core repositories with work reached {{progress:2026-10-09.repos_core}}; {{progress:2026-10-09.repos_core_new}} of them appeared after the first day. In total, {{progress:2026-10-09.repos_active}} repositories with work are counted.

{{figure:2026-10-09.cumulative}}

## Where the work happened

A row is a repository, a cell is a day; colour is the number of commits on one scale for all rows. The core is on top, control and the support layer below. Forks of other people's projects, personal repositories and the archive are not counted.

{{figure:2026-10-09.repos}}

## Who writes the code

Most commits are made by Claude sessions: {{progress:2026-10-09.ai_share}}. Commits under the author's name include both manual work and other tools under the author's account; git cannot tell them apart. What the author does shows not in the commit count but in the decision logs below.

{{figure:2026-10-09.authorship}}

## Parallel work in branches

Each AI session works in its own branch and merges the result into the main branch through a pull request in small batches. In the first three weeks commits went into no more than {{progress:2026-10-09.branches_first_weeks}} working branches a day; at the peak, into {{progress:2026-10-09.peak_branches}}. A commit made directly on the main branch does not count as a working branch. The median time from a batch's first commit to the merge is {{progress:2026-10-09.pr_median_minutes}}; {{progress:2026-10-09.pr_within_hour}} of pull requests were merged within an hour.

An unmerged branch is not a result yet, so it is shown separately, as of the snapshot. Branch history can only be reconstructed from merges and surviving branches: deleted unmerged branches are not counted.

{{figure:2026-10-09.parallel}}

## Code, tests and documents

Lines of text files in the core's main branches at the end of each day: {{progress:2026-10-09.lines_start}} at the start, {{progress:2026-10-09.lines_end}} now. The jumps on 3–4 and 6 October are new core repositories with their own code and documents. This is the core's volume: control and the support layer are not included. Volume shows the scale of the implementation, not its quality. Data, schemas and run logs are not included.

{{figure:2026-10-09.codebase}}

## Checks

What checks the code grew along with it: lines in the core's test files went from {{progress:2026-10-09.tests_start}} to {{progress:2026-10-09.tests_end}}. Galdhold has its own automated check command: according to the game's README there were {{progress:2026-10-09.hostcheck_start}} checks, now {{progress:2026-10-09.hostcheck_end}}.

These are different measures, and there is no overall quality score here. Test lines do not compare projects with each other, and the Galdhold check count is the project's own record on the dates it changed; there are no values between those dates.

{{figure:2026-10-09.verification}}

## Decisions and knowledge

Until {{progress:2026-10-09.decisions_first}} the core had no decision logs; at the snapshot they hold {{progress:2026-10-09.decisions_end}} entries. DPprofile is {{fact:PRF-WHAT}}. DPmeta holds {{fact:META-WHAT}}.

{{figure:2026-10-09.knowledge}}

## Ecosystem map

The ecosystem has three parts: the core — the upper part of the snapshot table above, control and the support layer — its lower part. Control stands apart: DPmetaApp is {{fact:APP-WHAT}}. The support layer: DPvisualisation is {{fact:VIS-WHAT#1}}; DPmonetization is {{fact:MON-WHAT}}. The map below shows layers by purpose: control has its own layer, and the support layer sits in the engineering foundation.

The repositories are independent: {{fact:META-NO-DEPS}}. Each builds and is checked on its own; they are connected by decisions, contracts and facts. A diagram of the links, built from data, is in the "Ecosystem" section.

{{figure:2026-10-09.ecosystem}}

## Milestones

Milestones are events with sources: a decision, a release, a measurement, a new repository. They are not measurements, and on the daily chart they are only marked.

{{figure:2026-10-09.timeline}}

## How it was measured

The dataset is collected by the system's own tool from repository clones; it is stored with the site, and the page is built from it at build time, without calls to GitHub. Only the current system is counted; the archive — earlier projects and the removed ones — is given in the method only as a number. A private project without a public name is counted only in totals; branch names, commit messages and the time of day of commits are not published. Later snapshots are new datasets with the same formulas; past ones are not recalculated.

{{figure:2026-10-09.method}}
