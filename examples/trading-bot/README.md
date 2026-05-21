# trading-bot

Minimal Syncler sender example. Sends synthetic trading reports to a paired user every 30 minutes.

## Setup

```sh
pip install -e ../../sdk-python
python bot.py register     # one-time
python bot.py pair         # writes pairing.png; scan in the Syncler app
python bot.py set-pairing <user_id> <pairing_key_hex>
python bot.py loop         # sends every SYNCLER_TICK_SECONDS (default 1800)
```

`state.json` is written next to `bot.py` and holds `sender_id`, `user_id`,
`pairing_key_hex`, `plugin_row_id`. Delete it to reset.

## Publishing the plugin

This example doesn't ship a plugin; it just exercises the sender side. To
make incoming messages render on the phone, you'd:

1. Write a `TradingPlugin` following the structure in `docs/integration-guide.md`,
   build + sign it.
2. `client.publish_plugin(...)` to get a `plugin_row_id`.
3. Set `plugin_row_id` in `state.json`.
