import { BasePlugin, registerPlugin } from "@syncler/plugin-sdk";
import manifest from "../manifest.json" with { type: "json" };

// The hello-world plugin uses the native template renderer
// (manifest.renderer = "template"), so the host draws the card
// without calling into JS at all. This file exists for two reasons:
//
//   1. The host requires every published plugin to have a signed
//      bundle — even template-renderer plugins ship a stub bundle so
//      the publish-time signature gate is uniform.
//   2. The plugin can OPTIONALLY override `render()` to inject
//      custom HTML in the detail view. The hello-world plugin
//      doesn't — the template manifest's `fields` block is enough.
//
// For a real plugin that needs JS (`renderer: "script"`), this is
// where your `render(payload)` + `onAction` + `onMessage` go.
class HelloWorldPlugin extends BasePlugin {
  static manifest = manifest;
  render(): string {
    // Unused on the host because renderer == "template", but the
    // SDK requires the override.
    return "<p>hello world</p>";
  }
}

registerPlugin(new HelloWorldPlugin());
