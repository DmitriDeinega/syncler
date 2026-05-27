import { network as networkApi } from './network';
import {
  notifications as notificationsApi,
  showNotification as showNotificationApi,
} from './notifications';
import {
  callPlatform,
  callPlatformSync,
  getPlatformBridge,
  type CameraCaptureOptions,
  type FilePickOptions,
  type GalleryPickOptions,
  type LocationCurrentOptions,
} from './platform';
import { storage as storageApi } from './storage';
import { DismissBehavior } from './enums';

export {
  BasePlugin,
  type DismissAction,
  type NotificationDescriptor,
  type NotificationEvent,
} from './base-plugin';
export {
  clearRegisteredPlugin,
  dispatchPluginHook,
  getRegisteredPlugin,
  installBridgeDispatcher,
  registerPlugin,
  type DispatchHook,
} from './bridge';
export { Capability, DismissBehavior, NotificationImportance } from './enums';
export {
  ManifestValidationError,
  assertPluginManifest,
  getRegisteredManifest,
  isPluginManifest,
  validatePluginManifest,
  type PluginManifest,
  type ValidatePluginManifestOptions,
} from './manifest';
export {
  EndpointNotDeclaredError,
  assertEndpointDeclared,
  isEndpointDeclared,
  network,
} from './network';
export { notifications, showNotification } from './notifications';
export {
  PlatformError,
  callPlatform,
  callPlatformSync,
  getPlatformBridge,
  type CameraCaptureOptions,
  type CurrentLocation,
  type FilePickOptions,
  type GalleryPickOptions,
  type LocationCurrentOptions,
  type PlatformBridge,
  type ShowNotificationOptions,
  type StorageOptions,
  type StorageScope,
} from './platform';
export { storage } from './storage';
export {
  HOST_PREVIEW_KEY,
  HOST_PREVIEW_LIMITS,
  HostPreviewValidationError,
  validateHostPreview,
  type HostPreview,
} from './preview';

/**
 * Type-safe SDK wrapper around the host-injected `window.platform` bridge.
 */
export const platform = {
  /** Shows a host notification. */
  showNotification: showNotificationApi,
  /** Typed key/value storage wrapper. */
  storage: storageApi,
  /** Typed network wrapper with manifest endpoint enforcement. */
  network: networkApi,
  /** Typed notification helper namespace. */
  notifications: notificationsApi,
  /** Camera APIs exposed by the host bridge. */
  camera: {
    /** Captures an image through the host camera UI. */
    capture(opts?: CameraCaptureOptions) {
      return callPlatform('camera.capture', (bridge) =>
        bridge.camera.capture(opts)
      );
    },
  },
  /** Gallery APIs exposed by the host bridge. */
  gallery: {
    /** Picks one or more gallery items through the host UI. */
    pick(opts?: GalleryPickOptions) {
      return callPlatform('gallery.pick', (bridge) =>
        bridge.gallery.pick(opts)
      );
    },
  },
  /** File APIs exposed by the host bridge. */
  file: {
    /** Picks a file through the host UI. */
    pick(opts?: FilePickOptions) {
      return callPlatform('file.pick', (bridge) => bridge.file.pick(opts));
    },
  },
  /** Location APIs exposed by the host bridge. */
  location: {
    /** Reads the current location through the host bridge. */
    current(opts?: LocationCurrentOptions) {
      return callPlatform('location.current', (bridge) =>
        bridge.location.current(opts)
      );
    },
  },
  /** Message response APIs exposed by the host bridge. */
  message: {
    /** Sends an action response payload back through the host bridge. */
    respond(actionId: string, payload: unknown) {
      return callPlatform('message.respond', (bridge) =>
        bridge.message.respond(actionId, payload)
      );
    },
    /** Sets host dismiss behavior for the active message. */
    dismissBehavior(behavior: DismissBehavior) {
      return callPlatformSync('message.dismissBehavior', (bridge) =>
        bridge.message.dismissBehavior(behavior)
      );
    },
  },
  /**
   * V3 #14 — two-way WebSocket channel. Plugin's bundle code
   * calls `connect(name)` to open / `send` an opaque V2
   * envelope / `close` when done. Incoming messages reach
   * the plugin's `onLiveMessage` hook (NOT this handle) — the
   * SDK side just exposes the imperative connect / send /
   * close surface.
   *
   * V3 #15 — `subscribe(name)` is sugar over `connect(name)`.
   * Same wire shape; just emphasizes that the caller cares
   * about reads, not writes.
   *
   * Delivery is best-effort + ephemeral; missed messages are
   * NOT replayed across reconnects. Use the inbox path for
   * authoritative state.
   */
  live: {
    connect(channel: string) {
      return callPlatform('live.connect', (bridge) =>
        bridge.live.connect(channel),
      );
    },
    subscribe(channel: string) {
      // V3 #15 — sugar over connect(). Same wire path.
      return callPlatform('live.connect', (bridge) =>
        bridge.live.connect(channel),
      );
    },
    send(channel: string, envelopeBase64: string) {
      return callPlatform('live.send', (bridge) =>
        bridge.live.send(channel, envelopeBase64),
      );
    },
    close(channel: string) {
      return callPlatform('live.close', (bridge) =>
        bridge.live.close(channel),
      );
    },
  },
  /** Host bridge version. */
  get version() {
    return getPlatformBridge().__version__;
  },
};
