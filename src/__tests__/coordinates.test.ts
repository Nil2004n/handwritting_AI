/** Coordinate mapping: contain fit with letterboxing. */
import { computeContainMapping, isBoundsInsideImage, mapBoundsToDisplay } from '../utils/coordinates';

describe('computeContainMapping', () => {
  test('portrait source in a wide container is height-bound with side bars', () => {
    const m = computeContainMapping(900, 1200, 800, 400);
    const scale = 400 / 1200;
    expect(m.scale).toBeCloseTo(scale);
    expect(m.displayWidth).toBeCloseTo(900 * scale);
    expect(m.displayHeight).toBeCloseTo(400);
    expect(m.offsetY).toBeCloseTo(0);
    expect(m.offsetX).toBeCloseTo((800 - 900 * scale) / 2);
  });

  test('landscape source in a tall container is width-bound with top/bottom bars', () => {
    const m = computeContainMapping(1200, 900, 400, 800);
    const scale = 400 / 1200;
    expect(m.scale).toBeCloseTo(scale);
    expect(m.displayWidth).toBeCloseTo(400);
    expect(m.displayHeight).toBeCloseTo(900 * scale);
    expect(m.offsetX).toBeCloseTo(0);
    expect(m.offsetY).toBeCloseTo((800 - 900 * scale) / 2);
  });

  test('degenerate inputs map to a zero mapping instead of NaN', () => {
    const m = computeContainMapping(0, 1200, 800, 400);
    expect(m).toEqual({
      scale: 0,
      offsetX: 0,
      offsetY: 0,
      displayWidth: 0,
      displayHeight: 0,
    });
  });
});

describe('mapBoundsToDisplay', () => {
  test('applies scale and letterbox offset to a block rect', () => {
    const m = computeContainMapping(900, 1200, 800, 400);
    const rect = mapBoundsToDisplay({ x: 60, y: 115, width: 780, height: 270 }, m);
    expect(rect.x).toBeCloseTo(m.offsetX + 60 * m.scale);
    expect(rect.y).toBeCloseTo(m.offsetY + 115 * m.scale);
    expect(rect.width).toBeCloseTo(780 * m.scale);
    expect(rect.height).toBeCloseTo(270 * m.scale);
  });
});

describe('isBoundsInsideImage', () => {
  test('accepts exact-fit rects, rejects overflow and empty rects', () => {
    expect(isBoundsInsideImage({ x: 0, y: 0, width: 900, height: 1200 }, 900, 1200)).toBe(true);
    expect(isBoundsInsideImage({ x: 60, y: 115, width: 780, height: 270 }, 900, 1200)).toBe(true);
    expect(isBoundsInsideImage({ x: 800, y: 0, width: 200, height: 100 }, 900, 1200)).toBe(false);
    expect(isBoundsInsideImage({ x: -1, y: 0, width: 10, height: 10 }, 900, 1200)).toBe(false);
    expect(isBoundsInsideImage({ x: 0, y: 0, width: 0, height: 10 }, 900, 1200)).toBe(false);
  });
});
