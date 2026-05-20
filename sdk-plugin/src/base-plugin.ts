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
  async onMessage(_decryptedPayload: unknown): Promise<NotificationDescriptor | void> {
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
}
