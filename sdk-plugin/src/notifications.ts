import { callPlatform, type ShowNotificationOptions } from './platform';

/**
 * Typed wrapper for `platform.showNotification`.
 */
export const notifications = {
  /**
   * Shows a host notification.
   */
  show(opts: ShowNotificationOptions): Promise<void> {
    return callPlatform('showNotification', (bridge) => bridge.showNotification(opts));
  },
};

/**
 * Shows a host notification.
 */
export function showNotification(opts: ShowNotificationOptions): Promise<void> {
  return notifications.show(opts);
}
