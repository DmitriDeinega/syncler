import {
  BasePlugin,
  Capability,
  DismissBehavior,
  registerPlugin,
  type HostPreview,
  type PluginManifest,
} from '@syncler/plugin-sdk';

// Phase 5b -- full round-trip example plugin.
//
// Pairs with examples/trading-bot/bot.py:
//   - bot.py publishes this bundle via client.publish_plugin(...) and
//     persists the returned plugin_row_id in state.json
//   - bot.py loop sends payloads matching TradingReport below
//   - this plugin renders the detail view + an "Acknowledge" button
//   - the action handler POSTs back to bot.py's /api/ack endpoint
//   - bot.py mutates state.json (ack_count + ack_history) so the
//     round-trip is visible without setting up a database

interface TradingReport {
  hostPreview: HostPreview;
  as_of: string;        // ISO8601 UTC
  pnl: number;          // dollars
  open_positions: number;
  headline: string;
  // Sent by bot.py. The plugin echoes this back in the action POST
  // so the bot can correlate acks to specific reports.
  message_id: string;
  // The bot's `/api/ack` URL. The sender controls this so the same plugin
  // bundle works against Android emulator (10.0.2.2), a physical phone
  // on the LAN (192.168.x.x), or USB + `adb reverse` (localhost), without
  // rebuilding/republishing. The host's network bridge still enforces
  // declaredEndpoints below -- the URL must match one of those globs.
  ack_url: string;
}

class TradingPlugin extends BasePlugin {
  static manifest: PluginManifest = {
    id: 'com.trading.app',
    name: 'Trading Bot',
    version: '1.0.0',
    senderId: '<your-sender-id>',
    bundleHash: 'UNSIGNED-PLACEHOLDER-REPLACE-WITH-SIGN-BUNDLE',
    signature: 'UNSIGNED-PLACEHOLDER-REPLACE-WITH-SIGN-BUNDLE',
    declaredCapabilities: [Capability.NETWORK],
    // `*` in host position = one segment, no dots/slashes. Four dotted
    // wildcards cover IPv4 (10.0.2.2 emulator, 192.168.x.x LAN). The
    // `localhost` entry covers `adb reverse tcp:8001 tcp:8001`. The
    // `*.example.com` entry is a placeholder for the production HTTPS
    // endpoint -- replace with your real host when shipping.
    declaredEndpoints: [
      'http://*.*.*.*:8001/api/*',
      'http://localhost:8001/api/*',
      'https://*.example.com/api/*',
    ],
    dismissBehavior: DismissBehavior.DISMISS_LOCAL_ONLY,
    minPlatformVersion: '1.0.0',
  };

  render(payload: TradingReport): string {
    const pnlColor = payload.pnl >= 0 ? '#0a8a30' : '#b30038';
    const sign = payload.pnl >= 0 ? '+' : '';
    return `
      <div style="font-family:system-ui,sans-serif;padding:16px;color:#111">
        <h2 style="margin:0 0 4px 0">${escapeHtml(payload.hostPreview.title)}</h2>
        <div style="color:#666;margin-bottom:12px">${escapeHtml(new Date(payload.as_of).toLocaleString())}</div>
        <div style="font-size:32px;font-weight:600;color:${pnlColor};margin-bottom:8px">${sign}$${payload.pnl.toFixed(2)}</div>
        <div style="margin-bottom:8px">Open positions: ${payload.open_positions}</div>
        <div style="margin-bottom:16px">${escapeHtml(payload.headline)}</div>
        <button id="ack" style="font-size:16px;padding:10px 18px;border:0;background:#1769ff;color:#fff;border-radius:8px;cursor:pointer">
          Acknowledge
        </button>
        <div id="status" style="margin-top:12px;color:#666;font-size:14px"></div>
      </div>
      <script>
        const messageId = ${JSON.stringify(payload.message_id)};
        // ack_url is sender-supplied per-message. The host's network bridge
        // still enforces declaredEndpoints -- this just chooses among them.
        const ackUrl = ${JSON.stringify(payload.ack_url)};
        const btn = document.getElementById('ack');
        const status = document.getElementById('status');
        btn.onclick = async () => {
          btn.disabled = true;
          status.textContent = 'Sending...';
          try {
            const response = await platform.network.fetch(ackUrl, {
              method: 'POST',
              headers: { 'Content-Type': 'application/json' },
              body: JSON.stringify({ message_id: messageId, acted_at: new Date().toISOString() }),
            });
            if (response.ok) {
              status.textContent = 'Acknowledged.';
            } else {
              status.textContent = 'Failed: HTTP ' + response.status;
              btn.disabled = false;
            }
          } catch (err) {
            status.textContent = 'Error: ' + (err && err.message || err);
            btn.disabled = false;
          }
        };
      </script>
    `;
  }
}

registerPlugin(new TradingPlugin());

function escapeHtml(value: string): string {
  return value.replace(/[&<>"']/g, (c) => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  }[c]!));
}
