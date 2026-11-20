/**
 * App-layer resolution of fixture image keys to bundled RN assets.
 *
 * This is the ONLY place that maps domain asset keys to `require(...)`d
 * image modules. Domain and fixture code never touch filesystem paths or
 * remote URLs; the Metro bundler includes these PNGs in the APK.
 */

export const IMAGE_ASSETS = {
  prose: require('../fixtures/images/prose.png'),
  numericals: require('../fixtures/images/numericals.png'),
  table: require('../fixtures/images/table.png'),
  equation: require('../fixtures/images/equation.png'),
  mixed: require('../fixtures/images/mixed.png'),
} as const;

export type ImageAssetKey = keyof typeof IMAGE_ASSETS;

export function resolveImageAsset(key: string) {
  const asset = (IMAGE_ASSETS as Record<string, unknown>)[key];
  if (!asset) {
    throw new Error(`Unknown image asset key: ${key}`);
  }
  return asset;
}
