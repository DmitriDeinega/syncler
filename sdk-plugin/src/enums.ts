/**
 * Host-side behavior to apply when a rendered notification is dismissed.
 */
export enum DismissBehavior {
  DISMISS_ALL = 'dismiss_all',
  DISMISS_LOCAL_ONLY = 'dismiss_local_only',
  MARK_READ_ALL = 'mark_read_all',
  CUSTOM_CALLBACK = 'custom_callback',
}

/**
 * Runtime capabilities a plugin may request in its manifest.
 */
export enum Capability {
  NETWORK = 'network',
  STORAGE = 'storage',
  CAMERA = 'camera',
  GALLERY = 'gallery',
  FILE = 'file',
  LOCATION = 'location',
  BACKGROUND_EXEC = 'background-exec',
}

/**
 * Visual and delivery importance for notifications shown by the host.
 */
export enum NotificationImportance {
  LOW = 'low',
  DEFAULT = 'default',
  HIGH = 'high',
}
