import { DismissBehavior, NotificationImportance } from './enums';

/**
 * Storage partition available to plugin key/value storage.
 */
export type StorageScope = 'device' | 'user';

/**
 * Options accepted by `platform.storage.*`.
 */
export interface StorageOptions {
  /** Storage partition. Defaults are host-defined. */
  scope?: StorageScope;
}

/**
 * Notification payload accepted by the native bridge.
 */
export interface ShowNotificationOptions {
  /** Notification title. */
  title: string;
  /** Notification body. */
  body: string;
  /** Host notification importance. */
  importance?: `${NotificationImportance}`;
  /** Optional grouping key used by the host notification surface. */
  groupId?: string;
}

/**
 * Camera capture options.
 */
export interface CameraCaptureOptions {
  /** Capture quality from 0 to 1 when supported by the host. */
  quality?: number;
}

/**
 * Gallery picker options.
 */
export interface GalleryPickOptions {
  /** Accepted MIME types, for example `image/png`. */
  mimeTypes?: string[];
  /** Allows selecting more than one item. */
  multiple?: boolean;
}

/**
 * File picker options.
 */
export interface FilePickOptions {
  /** Accepted MIME types, for example `application/pdf`. */
  mimeTypes?: string[];
}

/**
 * Location request options.
 */
export interface LocationCurrentOptions {
  /** Requested accuracy tier. */
  accuracy?: 'low' | 'high';
}

/**
 * Location returned by the host bridge.
 */
export interface CurrentLocation {
  /** Latitude in degrees. */
  lat: number;
  /** Longitude in degrees. */
  lng: number;
  /** Host-reported accuracy in meters. */
  accuracy: number;
}

/**
 * Raw bridge contract injected by the native WebView host.
 */
export interface PlatformBridge {
  showNotification(opts: ShowNotificationOptions): Promise<void>;
  storage: {
    get(key: string, opts?: StorageOptions): Promise<string | null>;
    set(key: string, value: string, opts?: StorageOptions): Promise<void>;
    delete(key: string, opts?: StorageOptions): Promise<void>;
  };
  network: {
    fetch(url: string, init?: RequestInit): Promise<Response>;
  };
  camera: {
    capture(
      opts?: CameraCaptureOptions
    ): Promise<{ blob: Blob; mimeType: string } | null>;
  };
  gallery: {
    pick(opts?: GalleryPickOptions): Promise<Blob[]>;
  };
  file: {
    pick(opts?: FilePickOptions): Promise<Blob | null>;
  };
  location: {
    current(opts?: LocationCurrentOptions): Promise<CurrentLocation | null>;
  };
  message: {
    respond(actionId: string, payload: unknown): Promise<void>;
    dismissBehavior(behavior: DismissBehavior): void;
  };
  /**
   * V3 #14/#15 — two-way live channel. Bridge implementations
   * route via the host's LiveChannelClient.
   */
  live: {
    /** Open a multiplexed channel. */
    connect(channel: string): Promise<{ channel: string; ok: boolean }>;
    /** `subscribe` is sugar over `connect` (V3 #15 spec). */
    subscribe?: (channel: string) => Promise<{ channel: string; ok: boolean }>;
    /** Send an opaque base64'd envelope. */
    send(channel: string, envelopeBase64: string): Promise<void>;
    /** Idempotent. */
    close(channel: string): Promise<void>;
  };
  __version__: string;
}

declare global {
  var platform: PlatformBridge | undefined;
  var __syncler_internal_dispatch:
    | ((
        hook: 'onMessage' | 'onAction' | 'onDismiss',
        args: unknown[]
      ) => Promise<unknown>)
    | undefined;
  interface Window {
    platform?: PlatformBridge;
    __syncler_internal_dispatch?: (
      hook: 'onMessage' | 'onAction' | 'onDismiss',
      args: unknown[]
    ) => Promise<unknown>;
  }
}

/**
 * Error raised when the native bridge is unavailable or rejects a call.
 */
export class PlatformError extends Error {
  /** Original rejection reason, when present. */
  readonly cause: unknown;

  constructor(message: string, cause?: unknown) {
    super(message);
    this.name = 'PlatformError';
    this.cause = cause;
  }
}

/**
 * Returns the host-injected bridge or throws a typed error when unavailable.
 */
export function getPlatformBridge(): PlatformBridge {
  const injected = globalThis.platform ?? globalThis.window?.platform;
  if (!injected) {
    throw new PlatformError('Syncler platform bridge is not available');
  }
  return injected;
}

/**
 * Wraps an async platform call and normalizes bridge rejections.
 */
export async function callPlatform<T>(
  operation: string,
  callback: (bridge: PlatformBridge) => Promise<T>
): Promise<T> {
  try {
    return await callback(getPlatformBridge());
  } catch (error) {
    if (error instanceof PlatformError) {
      throw error;
    }
    throw new PlatformError(
      `Syncler platform operation failed: ${operation}`,
      error
    );
  }
}

/**
 * Wraps a sync platform call and normalizes bridge failures.
 */
export function callPlatformSync<T>(
  operation: string,
  callback: (bridge: PlatformBridge) => T
): T {
  try {
    return callback(getPlatformBridge());
  } catch (error) {
    if (error instanceof PlatformError) {
      throw error;
    }
    throw new PlatformError(
      `Syncler platform operation failed: ${operation}`,
      error
    );
  }
}
