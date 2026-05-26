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
}
