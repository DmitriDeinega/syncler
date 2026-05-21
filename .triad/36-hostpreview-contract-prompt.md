# Consultation 36 — `hostPreview` payload contract

The user accepted the previous direction: the host draws inbox rows natively from structured metadata the sender embeds in the encrypted payload. Plugin `render()` is reserved for the tap-into detail view.

Design the exact contract.

## Output: a single concrete schema

Return three artifacts:

1. **TypeScript interface** (for `sdk-plugin`)
2. **Python `TypedDict`** (for `sdk-python` — what the sender passes into `client.send_to(...)`)
3. **Rendered example** for a lottery card and a trading-bot price alert

Use this scaffold; fill it in:

```ts
// sdk-plugin
export interface HostPreview {
  // ... your fields here, with JSDoc comments
}
```

```python
# sdk-python
class HostPreview(TypedDict, total=False):
    ...
```

## Constraints — apply each as a yes/no decision in your answer

- **Required vs optional**: Which fields MUST every sender provide? Anything optional should still produce a sensible row when absent.
- **Field length caps**: Pick concrete numbers. Title, subtitle, summary, searchText entries. Justify briefly.
- **`searchText`**: array of tokens, or single freeform string? Pick one. The host indexes this for global search across all plugins.
- **Priority**: enum (which values?) or omit entirely? If included, what does the host actually do with it?
- **Category**: freeform string, plugin-scoped enum, or omit?
- **Icon / color**: does the row carry visual identity beyond the sender name, or do we lean entirely on sender name / plugin avatar?
- **Timestamps**: which timestamp does the row display — server `sent_at`, sender-supplied, or both? Where does the sender-supplied timestamp belong if it exists?
- **Accessibility**: any field reserved for a screen-reader label distinct from visual text?
- **Fallback when `hostPreview` is missing entirely** (older senders not yet updated): what does the host render?
- **Cap on payload size** introduced by hostPreview (concrete bytes ceiling): the host has to decrypt every inbox message on every poll. We don't want sender free reign on 50KB of preview metadata per message.

## What to skip

- The detail-view contract is unchanged (plugin `render(payload)`).
- The decision IS made — `hostPreview` lives in the payload. Don't relitigate.
- Don't propose alternative shapes (object vs flat). Pick one and ship it.

## What to be aggressive about

If a field I listed in Codex's earlier sketch (`title / subtitle / summary / priority / category / searchText`) is wrong or redundant, cut it. If you'd ADD a field, justify it in one line. The goal is the smallest contract that makes the inbox great.

## Tone

Concrete and short. The previous consultation was 2000 words; this one should be ≤600.
