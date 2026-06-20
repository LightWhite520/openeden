# PSB Scenario Decoder (local-only persona research)

Decodes ATRI's KiriKiri/KAG `.ks.scn` scenario files (PSB binary format) into
readable JSON **locally**, so distilled, non-verbatim persona rules in
`persona/atri.yaml` can be grounded in how the character actually speaks.

> **This tooling is safe to commit. Its output is NOT.**
> The scripts here contain no game text. Everything they produce is written
> under `private_corpus/`, which is gitignored. Never commit decoded scenario
> text, and never paste verbatim dialogue into `persona/*.yaml` or the public
> repo. See `AGENTS.md` and the "Copyright Boundary" section below.

## Why this exists

The 34 `*.ks.scn` files in `private_corpus/atri_game_scripts_vol1/` are PSB
binaries — the dialogue is compressed inside the container, so plain string
extraction yields nothing (verified: `strings` finds zero ASCII or UTF-16 text).
Without decoding, any persona file is built on aggregate frequency stats, not on
the real script. This step closes that gap.

## Prerequisites

- **.NET Framework 4.8** — preinstalled on Windows 10/11. FreeMote tools require
  it (separate from the .NET 9 SDK used to build OpenEden).
- **FreeMote (FreeMoteToolkit)** — `PsbDecompile.exe`. `decode.ps1` resolves and
  downloads the latest release automatically via the GitHub API (asset name
  `Ulysses-FreeMoteToolkit-vX.Y.Z.zip`). To pin a version or work offline, pass
  `-FreeMoteUrl <zip>` or drop `PsbDecompile.exe` into the tool dir manually from
  <https://github.com/UlyssesWu/FreeMote/releases>.

## Format facts (verified for this game)

- All 34 `.scn` files begin with the raw `PSB\0` magic — **not** the `mdf`
  (MT19937) compressed/encrypted variant. So **no key is required**; a plain
  `PsbDecompile file.scn` works.
- GARbro already unpacked these from `vol1.xp3`, so the packed
  `info-psb -k {key}` workflow does **not** apply here — we decode loose files.

## Usage

From a normal PowerShell prompt (not the Git Bash used elsewhere):

```powershell
# 1. Fetch FreeMote + decode every .scn into the gitignored corpus.
.\tools\psb-decode\decode.ps1

# Output JSON lands in:  private_corpus/atri_decoded/
```

`decode.ps1` is idempotent: it skips the FreeMote download if already present and
skips `.scn` files already decoded. Re-run freely.

## After decoding: how to use the output

The decoded JSON is **local research material only**. The allowed extraction
path (per `AGENTS.md`) is:

1. Read decoded scenes locally to understand ATRI's actual voice — sentence
   rhythm, how she addresses the host, her literal-mindedness, the texture of
   her uncertainty, and how all of that shifts across her arc.
2. Derive **abstract, non-verbatim** rules and statistics from that reading.
3. Update `persona/atri.yaml` with distilled rules only.

The `AtriPersonaGuardTest` (in `core` jvmTest) enforces the boundary on the
committed persona file: no Japanese kana, no oversized quoted spans.

## Copyright boundary (hard rules)

**Allowed to leave this folder / enter the repo:**
- Statistics (sentence-length distributions, question/negation rates, …)
- Style summaries and abstract behavioral descriptions
- Frequency-derived observations

**Never commit / never paste into persona files:**
- Decoded scenario JSON or any raw extracted text
- Large dialogue excerpts or recognizable continuous lines
- Reconstructed scenes
- The FreeMote binaries themselves
