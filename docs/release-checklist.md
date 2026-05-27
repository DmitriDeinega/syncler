# Release / partner-drop checklist

This is the gate that runs before any artifact leaves the
development tree on its way to an integrating partner, an AWS
test instance, or a release tag.

The motivation is concrete: the V3 #16 cryptography import
landed on a "default extras" install path that wasn't exercised
by any test before the SDK shipped, and a partner integrator
hit the broken import on first call. The CI smoke gate caught
that class going forward, but the smoke gate only runs on push;
this checklist is what the human running the release confirms
**after** CI is green and **before** the tarball or package is
handed off.

Triad 162 codex addition. Six minutes per drop — cheap insurance.

---

## 1. Clean-room install (Python SDK)

In a throwaway shell (NOT your dev venv):

```sh
python -m venv /tmp/syncler-release-check
source /tmp/syncler-release-check/bin/activate   # Windows: .venv\Scripts\activate
pip install -e ./sdk-python
python -c "from syncler import Client; print('ok')"
pip install -e './sdk-python[broker]'
python -c "from syncler.broker import app; print('broker ok')"
deactivate
rm -rf /tmp/syncler-release-check
```

Both invocations MUST print `ok` / `broker ok`. Any
`ModuleNotFoundError`, `ImportError`, or cryptography-version
surprise here means the partner will see it too.

> **Why the throwaway venv:** your dev venv has every dep
> already cached. The partner hits a clean machine, which is
> exactly the path that broke the V3 #16 ship.

## 2. Plugin SDK build + test

```sh
cd sdk-plugin
npm ci
npm run build
npm test
```

Build must produce `dist/`. Tests must be green. A broken
`sdk-plugin` build means the hello-world (and every other
partner integration that depends on it via `file:`) cannot
compile.

## 3. Hello-world end-to-end build

```sh
cd examples/hello-world/plugin
npm ci
bash build.sh
test -f manifest.signed.json
```

`manifest.signed.json` proving the bundle signing pipeline
works end-to-end is the most-trusted "the build harness is OK"
signal we have. Partners run this exact script first.

## 4. Server endpoint sanity (against the target deployment)

Hit the deployment partner is about to consume:

```sh
curl -sf https://<deploy>/v1/server/webhook-public-key | jq
```

Expected: `{"public_key_base64": "...", "algorithm": "ed25519"}`.
This proves uvicorn is up, Caddy + Let's Encrypt are routing
cleanly, and the signing seed is configured. A `503`, a
`525` (Caddy SSL fail), or a connection error means **stop**
— the deploy isn't ready.

## 5. Docs link sanity

```sh
grep -rE '\]\((http|/)' docs/ examples/hello-world/README.md \
  | grep -vE '(claude\.ai|github\.com/anthropics|wikipedia|w3\.org)'
```

Then eyeball: do internal links still resolve to existing
files? Did any `examples/trading-bot/` references survive the
V3 #16 reorg? `integration-guide.md` and `hello-world/README.md`
are the two most-read partner-facing docs — they MUST be
consistent with current reality.

## 6. Partner-package staging area sanity

If the drop is a directory you'll zip and hand off (e.g.
`d:/desktop/for_oloia/`), confirm:

- [ ] No `node_modules/` (huge, useless, leaks transitive
  package list)
- [ ] No `__pycache__/` or `*.egg-info/` (Python build crud)
- [ ] No `.triad/` (internal review state — never partner-facing)
- [ ] No `.env`, `*.priv`, `*.pem` (secrets — the moment ONE of
  these leaves your machine you have a key-rotation incident)
- [ ] `README.md` has the right base URL for the partner's
  target environment
- [ ] APK is the right variant (`Syncler TEST` for partner
  dogfooding; `Syncler` for production handoff)

```sh
# Quick scan — should print nothing
find d:/desktop/for_oloia -name node_modules -o -name __pycache__ \
  -o -name '*.egg-info' -o -name .triad -o -name '*.env' \
  -o -name '*.priv' -o -name '*.pem'
```

## 7. Version + changelog stamp

Pyproject and `package.json` versions should match the tag
you're about to cut. Bump them in the same commit, not after.
If the SDK is `0.x.y`, increment the patch for every bug-fix
drop (V3 #16 → 0.3.1 → 0.3.2 etc).

## 8. After the partner has the artifact

Tell them, in the handoff message:
- the exact `pip install` / `npm install` commands they need
- the AWS endpoint URL (or other base URL)
- where they should report findings — file an issue on the
  Syncler repo, or message back with a numbered list (the
  Oloia integrator did the latter and it worked well)
- whether they should expect the `Syncler` or `Syncler TEST`
  variant on their device
- a known-good `hello-world` checkpoint they can compare against
  if their first integration breaks

---

This checklist is intentionally short. Anything more would
become a thing nobody runs. If a step here would have caught
a bug the partner reported, that's a sign to ADD a CI gate, not
make the checklist longer.
