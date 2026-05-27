import { DismissBehavior } from './enums';
import type { PluginManifest } from './manifest';

/**
 * Notification returned by `onMessage` for display by the host.
 */
export interface NotificationDescriptor {
  /** Notification title. */
  title: string;
  /** Notification body. */
  body: string;
  /** Optional host importance value. */
  importance?: 'low' | 'default' | 'high';
  /** Optional host grouping key. */
  groupId?: string;
}

/**
 * Dismiss action returned by a custom dismiss callback.
 */
export interface DismissAction {
  /** Dismiss behavior the host should apply after the callback. */
  behavior: DismissBehavior;
  /** Optional callback payload returned to the host. */
  payload?: unknown;
}

/**
 * Base class all Syncler plugins extend.
 */
export abstract class BasePlugin {
  /** Manifest for the plugin bundle. Subclasses must provide this static property. */
  static manifest: PluginManifest;

  /**
   * Renders card-view HTML for an inbound payload.
   */
  abstract render(data: unknown): string | Promise<string>;

  /**
   * Handles an inbound decrypted message. Defaults to no notification.
   */
  async onMessage(
    _decryptedPayload: unknown
  ): Promise<NotificationDescriptor | void> {
    return undefined;
  }

  /**
   * Handles a user action emitted by the rendered card. Defaults to no-op.
   */
  async onAction(_actionName: string, _payload: unknown): Promise<void> {
    return undefined;
  }

  /**
   * Handles notification dismissal when the manifest uses `CUSTOM_CALLBACK`. Defaults to no-op.
   */
  async onDismiss(_deviceId: string): Promise<DismissAction | void> {
    return undefined;
  }

  /**
   * V3 #14 — fires per inbound live-channel `message` frame
   * the host received on this plugin row. `envelopeBase64`
   * is the opaque V2-style envelope sealed for this device;
   * decode + open it with the V2 helpers in
   * `@syncler/plugin-sdk`. Override to react; defaults to
   * no-op so plugins that only use the inbox path don't
   * need to implement it.
   *
   * Spec: docs/live-channel.md "Message" frame.
   */
  async onLiveMessage(_channel: string, _envelopeBase64: string): Promise<void> {
    return undefined;
  }

  /**
   * V3 #14 — fires when the host receives an `error` frame
   * for a live channel (rate-limit, channel_name_invalid,
   * etc.). `code` is the error string from the server.
   * Defaults to no-op; override to surface diagnostics.
   */
  async onLiveError(_channel: string, _code: string): Promise<void> {
    return undefined;
  }

  /**
   * V3 #14 — fires when the host closes a live channel
   * (either side initiated; not the same as the WebSocket
   * closing). Override to clear local state; defaults to
   * no-op.
   */
  async onLiveClosed(_channel: string): Promise<void> {
    return undefined;
  }

  /**
   * Triad 168 / V3 #16 follow-up — fires when the host observes
   * a newer effective payload for THIS card (live-card upsert or
   * a merged card.patch). The host always delivers the latest
   * full payload, so plugins don't need to know whether the
   * change came from an upsert or a patch.
   *
   * Default behavior: nuke `document.body` and re-render via
   * `render(payload)`. That preserves the "fresh mount" contract
   * for plugins that haven't opted in to incremental updates,
   * BUT it destroys in-flight typing, scroll position, focus
   * state, and any non-bridged event listeners. A one-time
   * console.warn nudges plugin authors toward overriding this
   * with a partial-DOM-update implementation.
   *
   * If you override this, you OWN the UX trade-off: you can
   * patch text nodes, swap data attributes, animate, etc.
   * without losing user state. Return as soon as the DOM
   * reflects the new payload — the host doesn't await result
   * for cosmetic reasons but does observe the return value to
   * decide whether the dispatch succeeded.
   *
   * Spec: triad 168 agreement.
   */
  async onPayloadUpdate(payload: unknown): Promise<void> {
    if (typeof document === 'undefined') return;
    const warnState = (
      globalThis as { __syncler_onPayloadUpdate_warned__?: boolean }
    );
    if (!warnState.__syncler_onPayloadUpdate_warned__) {
      warnState.__syncler_onPayloadUpdate_warned__ = true;
      // eslint-disable-next-line no-console
      console.warn(
        '[Syncler] onPayloadUpdate fell back to a destructive ' +
          'render() + innerHTML replace. In-flight typing, scroll ' +
          'position, focus, and non-bridged event listeners will ' +
          'be lost on each payload update. Override ' +
          'onPayloadUpdate(payload) on your plugin to perform a ' +
          'partial DOM update instead.'
      );
    }
    const html = await this.render(payload);
    if (typeof html !== 'string') {
      throw new Error('plugin render() did not return a string');
    }
    document.body.innerHTML = html;
    // Mirror the initial-render bootstrap: inline <script> tags
    // injected via innerHTML don't execute by default. Re-create
    // them so the plugin's wire-up code (click handlers etc.) runs.
    // Mirrors RenderShell.kt's script-rewriting pass.
    document.body.querySelectorAll('script').forEach((oldScript) => {
      const newScript = document.createElement('script');
      for (const attr of Array.from(oldScript.attributes)) {
        newScript.setAttribute(attr.name, attr.value);
      }
      newScript.textContent = oldScript.textContent;
      oldScript.parentNode?.replaceChild(newScript, oldScript);
    });
  }
}
