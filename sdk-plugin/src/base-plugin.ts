import { DismissBehavior } from './enums';
import type { PluginManifest } from './manifest';

/**
 * Descriptor returned by [BasePlugin.getNotification] to drive an
 * Android notification. Returning `void` (or undefined) from
 * [BasePlugin.getNotification] suppresses the notification — the
 * plugin can decide which inbound events deserve user attention.
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
 * Event passed to [BasePlugin.getNotification]. Discriminated by
 * [kind]:
 *
 * - `message`: a fresh event-type message arrived. No prior payload.
 * - `card_arrived`: this device observed the first upsert for
 *   `cardKey`. `previousPayload` is always `null` by construction.
 * - `card_updated`: an upsert OR a merged card.patch produced a
 *   newer canonical payload for `cardKey`. `previousPayload` is the
 *   last payload THIS device observed (or `null` if the device
 *   missed prior versions). `updateSource` tells the plugin whether
 *   the change came from a full upsert or a field-level patch — most
 *   plugins won't care; included so notification logic that does
 *   care (e.g. "only notify on full state replacement") has it.
 *
 * V4 #21 / triad 169 — designed clean-slate; previous
 * `BasePlugin.onMessage` is subsumed by the `message` kind.
 */
export type NotificationEvent =
  | {
      kind: 'message';
      cardType: 'event';
      payload: unknown;
    }
  | {
      kind: 'card_arrived';
      cardType: 'live';
      cardKey: string;
      payload: unknown;
      previousPayload: null;
    }
  | {
      kind: 'card_updated';
      cardType: 'live';
      cardKey: string;
      payload: unknown;
      previousPayload: unknown | null;
      updateSource: 'upsert' | 'patch';
    };

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
   * V4 #21 — single notification entry point. Fires for every
   * inbound event the platform thinks MIGHT warrant a user-visible
   * notification (event-message arrival, live-card arrival, live-
   * card update). Return a [NotificationDescriptor] to post a
   * notification, return `void`/undefined to suppress.
   *
   * The plugin owns the "is this notification-worthy?" decision —
   * the platform stays content-blind. Examples:
   *
   * - High-frequency live card (per-second tick): return `void` for
   *   most updates; return a descriptor only on a meaningful
   *   state transition by comparing `payload` vs `previousPayload`.
   * - Event message: usually return a descriptor — these are
   *   delivered explicitly by the sender.
   * - First-arrival: most plugins return a descriptor; some prefer
   *   to keep the surface silent until a state change happens.
   *
   * Server-side wake-up policy is declared in the plugin manifest's
   * `notifications` block: `messageReceived` and `cardArrived`
   * default to `true`; `cardUpdated` defaults to `false` so
   * high-frequency cards don't drain battery on every patch.
   * Plugins that want update notifications must opt in.
   *
   * Triad 169 spec.
   */
  async getNotification(
    _event: NotificationEvent
  ): Promise<NotificationDescriptor | void> {
    return undefined;
  }

  /**
   * Handles a user-initiated action emitted by the rendered card
   * (e.g. a button tap that the plugin's HTML wired up via
   * `data-action`). Defaults to no-op.
   *
   * V4 #21 rename: was `onAction` pre-V4-#21. The new name
   * disambiguates from server-pushed events handled by
   * [getNotification].
   */
  async onUserAction(_actionName: string, _payload: unknown): Promise<void> {
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
