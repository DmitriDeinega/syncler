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
 *
 * Phase 12 (V2 #10) split `LOCATION` into `LOCATION_COARSE` and
 * `LOCATION_FINE` per the least-privilege principle. Manifests
 * declaring legacy `location` are rejected at publish time;
 * manifests declaring BOTH `location.coarse` and `location.fine`
 * are also rejected — plugins should declare `location.fine` and
 * check the `precision` field on the result for OS-downgraded
 * approximate fixes. See docs/plugin-capability-expansion.md.
 */
export enum Capability {
  NETWORK = 'network',
  STORAGE = 'storage',
  CAMERA = 'camera',
  GALLERY = 'gallery',
  FILE = 'file',
  LOCATION_COARSE = 'location.coarse',
  LOCATION_FINE = 'location.fine',
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
