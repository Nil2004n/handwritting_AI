/**
 * Source-pixel <-> displayed-point mapping for "contain" image fit.
 *
 * The review screen renders the source image with `resizeMode="contain"`,
 * which scales uniformly and centers the image, leaving letterbox bars on
 * one axis. Overlay rectangles must use this mapping so highlights align
 * with the visible image instead of the raw container.
 */

import type { SourceBounds } from '../domain/document';

export interface ContainMapping {
  /** Uniform scale applied to source pixels. */
  scale: number;
  /** Horizontal letterbox offset in displayed points. */
  offsetX: number;
  /** Vertical letterbox offset in displayed points. */
  offsetY: number;
  /** Displayed image size in points (container minus letterbox). */
  displayWidth: number;
  displayHeight: number;
}

export function computeContainMapping(
  sourceWidth: number,
  sourceHeight: number,
  containerWidth: number,
  containerHeight: number,
): ContainMapping {
  if (
    !(sourceWidth > 0) ||
    !(sourceHeight > 0) ||
    !(containerWidth > 0) ||
    !(containerHeight > 0)
  ) {
    return {
      scale: 0,
      offsetX: 0,
      offsetY: 0,
      displayWidth: 0,
      displayHeight: 0,
    };
  }
  const scale = Math.min(containerWidth / sourceWidth, containerHeight / sourceHeight);
  const displayWidth = sourceWidth * scale;
  const displayHeight = sourceHeight * scale;
  return {
    scale,
    offsetX: (containerWidth - displayWidth) / 2,
    offsetY: (containerHeight - displayHeight) / 2,
    displayWidth,
    displayHeight,
  };
}

/** Map one source-pixel rect to displayed container points. */
export function mapBoundsToDisplay(
  bounds: SourceBounds,
  mapping: ContainMapping,
): { x: number; y: number; width: number; height: number } {
  return {
    x: mapping.offsetX + bounds.x * mapping.scale,
    y: mapping.offsetY + bounds.y * mapping.scale,
    width: bounds.width * mapping.scale,
    height: bounds.height * mapping.scale,
  };
}

/** True when the rect lies fully inside a W×H image. */
export function isBoundsInsideImage(
  bounds: SourceBounds,
  imageWidth: number,
  imageHeight: number,
): boolean {
  return (
    Number.isFinite(bounds.x) &&
    Number.isFinite(bounds.y) &&
    bounds.width > 0 &&
    bounds.height > 0 &&
    bounds.x >= 0 &&
    bounds.y >= 0 &&
    bounds.x + bounds.width <= imageWidth &&
    bounds.y + bounds.height <= imageHeight
  );
}
