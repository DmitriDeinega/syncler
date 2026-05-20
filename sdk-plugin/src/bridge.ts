import { BasePlugin } from './base-plugin';
import { assertPluginManifest, setRegisteredManifest } from './manifest';

/**
 * Hooks the native host may dispatch into a loaded plugin.
 */
export type DispatchHook = 'onMessage' | 'onAction' | 'onDismiss';

let registeredPlugin: BasePlugin | undefined;

/**
 * Registers the plugin instance that should receive native bridge dispatches.
 */
export function registerPlugin(plugin: BasePlugin): void {
  const manifest = (plugin.constructor as typeof BasePlugin).manifest;
  assertPluginManifest(manifest);
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
  try {
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
      default:
        return assertNever(hook);
    }
  } catch (error) {
    return JSON.stringify(serializeError(error));
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

function serializeError(error: unknown): { name: string; message: string; stack?: string } {
  if (error instanceof Error) {
    const result: { name: string; message: string; stack?: string } = {
      name: error.name,
      message: error.message,
    };
    if (error.stack) {
      result.stack = error.stack;
    }
    return result;
  }
  return {
    name: 'Error',
    message: String(error),
  };
}

function assertNever(value: never): never {
  throw new Error(`Unsupported dispatch hook: ${String(value)}`);
}
