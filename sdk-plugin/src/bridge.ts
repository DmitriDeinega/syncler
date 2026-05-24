import { BasePlugin } from './base-plugin';
import { assertPluginManifest, setRegisteredManifest } from './manifest';

/**
 * Hooks the native host may dispatch into a loaded plugin.
 *
 * 'render' returns the HTML string the host should inject into the card view.
 * The other three are lifecycle hooks the plugin implements on the BasePlugin
 * subclass.
 */
export type DispatchHook = 'onMessage' | 'onAction' | 'onDismiss' | 'render';

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
export async function dispatchPluginHook(hook: DispatchHook, args: unknown[]): Promise<unknown> {
  if (!registeredPlugin) {
    throw new Error('No Syncler plugin has been registered');
  }

  switch (hook) {
    case 'onMessage':
      return await registeredPlugin.onMessage(args[0]);
    case 'onAction':
      return await registeredPlugin.onAction(asString(args[0], 'actionName'), args[1]);
    case 'onDismiss':
      return await registeredPlugin.onDismiss(asString(args[0], 'deviceId'));
    case 'render':
      return await registeredPlugin.render(args[0]);
    default:
      return assertNever(hook);
  }
}

/**
 * Installs `__syncler_internal_dispatch` on `window`/`globalThis` for the native host.
 */
export function installBridgeDispatcher(): void {
  const dispatch = (hook: DispatchHook, args: unknown[]): Promise<unknown> => dispatchPluginHook(hook, args);
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

function assertNever(value: never): never {
  throw new Error(`Unsupported dispatch hook: ${String(value)}`);
}
