import { network as networkApi } from './network';
import { notifications as notificationsApi, showNotification as showNotificationApi } from './notifications';
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

export { BasePlugin, type DismissAction, type NotificationDescriptor } from './base-plugin';
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
export { EndpointNotDeclaredError, assertEndpointDeclared, isEndpointDeclared, network } from './network';
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
      return callPlatform('camera.capture', (bridge) => bridge.camera.capture(opts));
    },
  },
  /** Gallery APIs exposed by the host bridge. */
  gallery: {
    /** Picks one or more gallery items through the host UI. */
    pick(opts?: GalleryPickOptions) {
      return callPlatform('gallery.pick', (bridge) => bridge.gallery.pick(opts));
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
      return callPlatform('location.current', (bridge) => bridge.location.current(opts));
    },
  },
  /** Message response APIs exposed by the host bridge. */
  message: {
    /** Sends an action response payload back through the host bridge. */
    respond(actionId: string, payload: unknown) {
      return callPlatform('message.respond', (bridge) => bridge.message.respond(actionId, payload));
    },
    /** Sets host dismiss behavior for the active message. */
    dismissBehavior(behavior: DismissBehavior) {
      return callPlatformSync('message.dismissBehavior', (bridge) => bridge.message.dismissBehavior(behavior));
    },
  },
  /** Host bridge version. */
  get version() {
    return getPlatformBridge().__version__;
  },
};
