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

# Consultation 93 — Phase 6 plan (V1.5 DX follow-ups)

V1.5 DX track shipped end-to-end through consult 92 (Phase 5e
commit `22c4479`). Two documented loose ends remain. Phase 6
closes them both.

## Item A — Pending-pairing registry in `BrokerStorage`

### Current state

`sdk-python/syncler/broker_storage.py` ships a `BrokerStorage`
Protocol with `reserve(pairing_id)` / `complete(pairing_id, entry)`
/ `fetch(pairing_id)`. The shipped `InMemoryBrokerStorage.reserve`
is a no-op — `complete` accepts ANY UUID. As documented in
`docs/crypto-spec.md §9.3 "V1.5 deviation: fixed-config broker"`
and `sdk-python/syncler/broker/app.py` doc-string: the V1.5
broker cannot reject envelopes whose `pairing_id` was never
reserved. The mitigations are UUID entropy (122 bits) + the
mandatory `rate_limiter` hook.

### Proposed change

`BrokerStorage.reserve(pairing_id)` becomes a real CAS operation:
- First call stores the pairing_id in a "pending" state.
- Repeat calls are idempotent.
- The Protocol gains a way to tell "pending" from "unknown" —
  cleanest is `is_reserved(pairing_id: str) -> bool`.

`InMemoryBrokerStorage` implements:
- A `set[str]` of reserved IDs.
- `reserve` adds; `is_reserved` returns membership; `complete`
  raises `UnknownPairingIdError` when called for an ID that was
  never reserved.

Broker handler at `sdk-python/syncler/broker/app.py`:
- Before decrypt, call `storage.is_reserved(pairing_id)` and
  return HTTP **404** with opaque body if False.
- This shifts the broker from "accept any UUID" to "accept only
  pre-registered UUIDs". Attacker with public bootstrap key can
  still mint valid envelopes but can't get past the pending check
  for a random UUID.

`Client.create_pairing_qr(sender_broker_url=...)`:
- Already constructs `pending_pairing_id` server-side via the
  `/v1/pairing/initiate` round-trip. Add a line:
  `if self._broker_storage is not None and sender_broker_url is not None:
       self._broker_storage.reserve(self._pending_pairing_id)`

Open Q: where does the broker app + the sender share state? In
fixed-config mode both processes use the same storage instance
(same Postgres URL / Redis URL). The Client writes via `reserve`;
the broker app reads via `is_reserved`. For `InMemoryBrokerStorage`,
both sides must run in the same Python process (the trading-bot
case below). For production: documented as requiring a shared
backing store.

Docs to update:
- `docs/crypto-spec.md §9.3` — remove or shrink the "V1.5
  deviation" note; mention the registry is now first-class.
- `docs/integration-guide.md §8.5` — add a "Production storage"
  callout pointing at the pending-state requirement; note that
  `InMemoryBrokerStorage` only works for single-process dev.
- `sdk-python/syncler/broker/app.py` doc-string — strike the
  "V1.5 fixed-config deviation" paragraphs.

Tests:
- `sdk-python/tests/test_broker_app.py`: new test for 404 on
  unknown pairing_id (envelope is cryptographically valid but the
  pairing was never reserved).
- `sdk-python/tests/test_bootstrap.py`: extend the existing
  `test_in_memory_broker_storage_cas` to cover reserve →
  is_reserved → complete; assert UnknownPairingIdError when
  complete is called without prior reserve.

## Item B — Trading-bot migration to `Client.wait_for_pairing`

### Current state

`examples/trading-bot/bot.py` uses the V1 manual pairing flow:
`pair` writes a QR, `set-pairing <user_id> <pairing_key_hex>`
accepts the values the user copies off the device. The README +
the ROADMAP both note "the example still uses V1 manual pairing;
migrating it to `Client.wait_for_pairing` is a follow-up V1.5
dogfood task" (Phase 5e commit, with explicit Codex 91 callout).

### Proposed change

Replace the `set-pairing` step with the automated flow:
- New CLI subcommand `bootstrap-init` (or fold into existing
  `register`): generates an X25519 bootstrap keypair, registers
  it via `client.register_bootstrap_key`, persists private key
  to `~/.syncler/keys/trading-bot-bootstrap.bin` (0600), public
  key to `state.json`.
- New CLI subcommand `broker`: launches the `syncler.broker.make_app`
  FastAPI app via uvicorn on localhost. Or fold into `loop` so
  one command runs both the broker thread and the loop.
- `pair` subcommand:
  - Accepts a `--sender-broker-url` arg (default
    `http://localhost:8002/` for dev).
  - Calls `client.create_pairing_qr(ttl_seconds=300,
    sender_broker_url=...)`. The QR now includes the bootstrap
    metadata.
  - Spawns/uses a thread running the broker app sharing the
    in-memory storage.
  - Calls `client.wait_for_pairing(timeout_seconds=120)` and
    prints the result.
- `set-pairing` subcommand: KEEP (fallback for when broker is
  unreachable or for users following the V1 path).

Open Q on Item B: should the bot use `InMemoryBrokerStorage` and
run the broker app in-process (one Python process, simple), or
should the README walk users through a separate uvicorn
invocation? In-process is simpler and matches the trading-bot
ethos of "smallest realistic example". Probably in-process via a
threading worker, with a docs callout that production splits
them across machines.

### Docs to update

- `examples/trading-bot/README.md` — rewrite the §3 ("Pair with
  a user") section to use the automated flow; keep `set-pairing`
  documented as the fallback.
- `docs/ROADMAP.md` #7 — strike the "(The example still uses
  the V1 manual pairing step; migrating it to
  `Client.wait_for_pairing` is a follow-up under V1.5 dogfood —
  see `examples/trading-bot/README.md`.)" parenthetical now that
  it's actually shipped.
- `docs/integration-guide.md §8` trailing pointer — strike "the
  example still uses V1 manual pairing".

## Phase 6 sequencing

Item A must land before Item B can be useful — the trading-bot
example wants to use the registry. Sequence: A first
(broker_storage + broker app + tests + docs), THEN B (bot.py +
README + roadmap + integration-guide cleanup). Both can be in
ONE Phase 6 commit since they're tightly coupled, OR split into
6a (registry) + 6b (bot migration). Preference?

## Open questions for reviewers

1. **Item A — `complete` policy when not reserved.** Should
   `complete` raise `UnknownPairingIdError`, or just return
   False (silent reject)? Raising is louder; returning False
   loses the ability for the handler to distinguish "unknown"
   from "idempotent replay" without an explicit `is_reserved`
   call. I lean **raise**.

2. **Item A — HTTP status for unknown pairing_id.** 404 is the
   natural REST choice. 401 (opaque) is also defensible since
   we already use 401 for "decrypt failed" and don't want to
   leak which class of rejection happened. I lean **404** with
   opaque body, on the grounds that knowing-a-pairing-is-pending
   is not sensitive (the UUID itself is the entropy guard).

3. **Item B — In-process vs out-of-process broker.** In-process
   via a thread is simpler; out-of-process matches production
   layout. I lean **in-process via thread** with a clear callout
   that production splits them.

4. **Phase 6 commit shape.** One commit vs 6a + 6b. I lean
   **one commit** since A is dead code without B exercising it.

5. **Should Phase 6 also revisit the `Client.create_pairing_qr`
   reservation call?** The Client currently has a
   `_pending_pairing_id` field set after the QR is built. Adding
   the `reserve(...)` call is a natural Phase 6 piece; flagging
   in case there's a layering concern I'm missing.

## Output

Per reviewer:
1. Per-item: GREEN / YELLOW / RED on the plan (Item A + Item B
   independently).
2. Answers to the five open questions.
3. Anything missing.
4. Anything new (security, footgun).

If dual-GREEN, implement → code review → commit.

Reply text only. Do NOT call any write/mutation tool.
