=================================================================
ABSOLUTE INSTRUCTION — READ THIS BEFORE DOING ANYTHING ELSE

You are in REVIEW MODE. Your ONLY output is a text reply to the
prompt below. You are FORBIDDEN from:

  - Writing or editing ANY file (no write_file, no edit_file, no
    str_replace, no create_file, no apply_diff).
  - Running ANY shell command that mutates state (no git commit,
    no git add, no git checkout, no git push, no git stash, no
    mkdir, no pip install, no npm install, no rm, no mv, no touch,
    no chmod).
  - Creating or modifying ANY directory.

You MAY use:
  - read_file / cat / Get-Content (to inspect files for context)
  - grep / ripgrep / Select-String (read-only search)
  - list_directory / ls / Get-ChildItem (read-only)
  - git status / git log / git diff / git show (read-only)

Reply text only.
=================================================================

# Consultation 92 — Phase 5e fix-ups (post-91)

Consultation 91 voted: **Gemini GREEN, Codex YELLOW on three
docs accuracy issues**. This consultation reviews the fix-ups.

## What Codex 91 caught

### YELLOW — ROADMAP commit ref mislabeled

> [docs/ROADMAP.md](D:/Projects/syncler/docs/ROADMAP.md:31) lists
> `2de2e4b` under "Phase 5a-1 (spec + vectors)", but `git show`
> confirms `2de2e4b` only adds `.triad/70-phase5-agreement.md`.
> The actual spec/vector commit is `57bb488`.

### YELLOW — Trading-bot overclaim

> [docs/ROADMAP.md](D:/Projects/syncler/docs/ROADMAP.md:34) and
> [docs/integration-guide.md](D:/Projects/syncler/docs/integration-guide.md:548)
> say `register` → `pair` → `publish-plugin` → `ack-server` + `loop`
> produces a real card. The actual example still requires manual
> pairing: `bot.py:96` creates a QR without `sender_broker_url`,
> prints `set-pairing`. The guide should include
> `set-pairing <user_id> <pairing_key_hex>` in that sequence.

### YELLOW — "POSTs the pairing key" reads as plaintext

> §8 step 5 says the app "POSTs the pairing key to your broker."
> Better to say it POSTs the encrypted bootstrap envelope
> containing the pairing key.

## Fix-ups applied

### `docs/ROADMAP.md` #6
Split the single bullet into properly-labeled sub-bullets:

```
- Phase 5 agreement: `2de2e4b`
- Phase 5a-1 (spec + vectors in `docs/crypto-spec.md §9`): `57bb488`
- Phase 5a-2 (server + Android crypto + SDK protocol foundation): `a9fe84e`
- Phase 5a-2.1 (Android pairing UX + FastAPI broker app + integration-guide §8.5): `7e5ec4d`
```

### `docs/ROADMAP.md` #7
Sequence now reads:

```
python bot.py register → pair → set-pairing <user_id> <pairing_key_hex>
→ publish-plugin → ack-server + loop
```

With an added parenthetical:

> (The example still uses the V1 manual pairing step; migrating
> it to `Client.wait_for_pairing` is a follow-up under V1.5
> dogfood — see `examples/trading-bot/README.md`.)

### `docs/integration-guide.md` §8 step 5 wording

V1.5 path now reads:

> the app POSTs an encrypted bootstrap envelope (containing
> `user_id` + `pairing_key`, sealed with the sender's X25519
> bootstrap public key) to your broker after fingerprint
> confirmation; your sender's `Client.wait_for_pairing(...)`
> returns the decrypted pair.

### `docs/integration-guide.md` §8 trailing pointer

Now includes `set-pairing` in the sequence:

```
python bot.py register → pair →
set-pairing <user_id> <pairing_key_hex> (the example still uses
V1 manual pairing) → publish-plugin → ack-server + loop
```

## What I need

Per reviewer:
1. Per-item: GREEN / YELLOW / RED on the three fix-ups.
2. Anything still missing.
3. Anything new.
4. Commit-readiness vote.

If dual-GREEN, the orchestrator commits Phase 5e — the final
V1.5 DX commit.

Reply text only. Do NOT call any write/mutation tool.
