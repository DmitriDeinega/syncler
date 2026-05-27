import { BasePlugin } from './base-plugin';
import { assertPluginManifest, setRegisteredManifest } from './manifest';

/**
 * Hooks the native host may dispatch into a loaded plugin.
 *
 * 'render' returns the HTML string the host should inject into the card view.
 * The other lifecycle hooks the plugin implements on the BasePlugin subclass.
 *
 * V3 #14 live-channel hooks (triad 158 bug 3 + bug 4 FIX): the Android
 * `LiveBridge` dispatches `onLiveMessage` / `onLiveError` / `onLiveClosed`
 * per incoming live frame. Prior to triad 158 these dispatches dead-ended
 * because the bridge here had no case for them.
 */
export type DispatchHook =
  | 'getNotification'
  | 'onUserAction'
  | 'onDismiss'
  | 'render'
  | 'onLiveMessage'
  | 'onLiveError'
  | 'onLiveClosed'
  | 'onPayloadUpdate';

let registeredPlugin: BasePlugin | undefined;

/**
 * Registers the plugin instance that should receive native bridge dispatches.
 *
 * The in-bundle source manifest cannot know its own bundleHash/signature
 * (those are produced by `tools/sign-bundle.ts` post-build and stored in
 * `manifest.signed.json`, not back into the JS source). The host has the
 * authoritative signed values from the server, so the runtime check here
 * accepts placeholder values. Publish-side validation (server, sign tool,
 * SDK helpers) keeps the strict 64/128-hex check.
 */
export function registerPlugin(plugin: BasePlugin): void {
  const manifest = (plugin.constructor as typeof BasePlugin).manifest;
  assertPluginManifest(manifest, { allowUnsignedPlaceholders: true });
  registeredPlugin = plugin;
  setRegisteredManifest(manifest);
}

/**
 * Returns the registered plugin instance, if any.
 */
export function getRegisteredPlugin(): BasePlugin | undefined {
  return registeredPlugin;
}

/**
 * Clears plugin registration. Intended for tests.
 *
 * @internal
 */
export function clearRegisteredPlugin(): void {
  registeredPlugin = undefined;
  setRegisteredManifest(undefined);
}

/**
 * Dispatches a host bridge hook into the registered plugin.
 */
export async function dispatchPluginHook(
  hook: DispatchHook,
  args: unknown[]
): Promise<unknown> {
  if (!registeredPlugin) {
    throw new Error('No Syncler plugin has been registered');
  }

  switch (hook) {
    case 'getNotification':
      // V4 #21 — single notification entry point. args[0] is the
      // NotificationEvent (already a JS object on the JS side; the
      // host serialized it as JSON and re-parsed via JSON.parse
      // before the dispatch call). Plugin returns
      // NotificationDescriptor | void.
      return await registeredPlugin.getNotification(args[0] as never);
    case 'onUserAction':
      return await registeredPlugin.onUserAction(
        asString(args[0], 'actionName'),
        args[1]
      );
    case 'onDismiss':
      return await registeredPlugin.onDismiss(asString(args[0], 'deviceId'));
    case 'render':
      return await registeredPlugin.render(args[0]);
    case 'onLiveMessage': {
      // Triad 158 bug 3 FIX: Android dispatches a single JSON
      // payload `{"channel": "...", "envelope": "<base64>"}`
      // — parse + fan out as positional args to the plugin.
      const payload = parseLivePayload(args[0], 'onLiveMessage');
      return await registeredPlugin.onLiveMessage(
        asString(payload.channel, 'channel'),
        asString(payload.envelope, 'envelope')
      );
    }
    case 'onLiveError': {
      // Triad 158 bug 4 FIX: same envelope shape (channel + code).
      const payload = parseLivePayload(args[0], 'onLiveError');
      return await registeredPlugin.onLiveError(
        asString(payload.channel, 'channel'),
        asString(payload.code, 'code')
      );
    }
    case 'onLiveClosed': {
      const payload = parseLivePayload(args[0], 'onLiveClosed');
      return await registeredPlugin.onLiveClosed(
        asString(payload.channel, 'channel')
      );
    }
    case 'onPayloadUpdate':
      // V3 #16 follow-up / triad 168 — fires when the host
      // observes a newer effective payload for the same live card
      // (upsert OR merged patch). The host always delivers the
      // full payload; plugins that don't override the default get
      // a destructive re-render via BasePlugin.onPayloadUpdate.
      return await registeredPlugin.onPayloadUpdate(args[0]);
  }
}

/**
 * Installs `__syncler_internal_dispatch` on `window`/`globalThis` for the native host.
 */
export function installBridgeDispatcher(): void {
  // The exported dispatcher accepts any string at the wire level —
  // host-side TypeScript / Kotlin doesn't know which hooks exist on
  // the loaded plugin. `dispatchPluginHook` is the narrowing
  // boundary: unknown hooks throw via `assertNever` in the switch.
  const dispatch = (hook: string, args: unknown[]): Promise<unknown> =>
    dispatchPluginHook(hook as DispatchHook, args);
  globalThis.__syncler_internal_dispatch = dispatch;
  if (globalThis.window) {
    globalThis.window.__syncler_internal_dispatch = dispatch;
  }
}

installBridgeDispatcher();

function asString(value: unknown, name: string): string {
  if (typeof value !== 'string') {
    throw new TypeError(`${name} must be a string`);
  }
  return value;
}

/**
 * Triad 158 — parses the Android-side single-argument JSON
 * payload for the live-channel hooks. Returns a record of the
 * extracted fields; callers `asString` the ones they need.
 */
function parseLivePayload(value: unknown, hookName: string): Record<string, unknown> {
  if (typeof value !== 'string') {
    throw new TypeError(`${hookName} payload must be a JSON string`);
  }
  try {
    const parsed = JSON.parse(value) as unknown;
    if (!parsed || typeof parsed !== 'object') {
      throw new Error('not an object');
    }
    return parsed as Record<string, unknown>;
  } catch (err) {
    throw new TypeError(`${hookName} payload is not valid JSON: ${String(err)}`);
  }
}

function assertNever(value: never): never {
  throw new Error(`Unsupported dispatch hook: ${String(value)}`);
}
