/**
 * Source image with selectable block-region overlay (Phase 0).
 *
 * The image is rendered with `resizeMode="contain"`. Highlight rectangles
 * are mapped through `computeContainMapping`/`mapBoundsToDisplay` so they
 * track the visible image, including letterbox offsets.
 */

import React, { useState } from 'react';
import { Image, Pressable, StyleSheet, Text, View } from 'react-native';
import type { SourceBounds } from '../domain/document';
import { computeContainMapping, mapBoundsToDisplay } from '../utils/coordinates';
import { resolveImageAsset } from '../app/imageAssets';

export interface OverlayRegion {
  id: string;
  label: string;
  bounds: SourceBounds;
}

interface Props {
  assetKey: string;
  imageWidth: number;
  imageHeight: number;
  regions: OverlayRegion[];
  selectedId: string | null;
  onSelect: (id: string) => void;
}

export function SourceImageView({
  assetKey,
  imageWidth,
  imageHeight,
  regions,
  selectedId,
  onSelect,
}: Props) {
  const [layout, setLayout] = useState({ width: 0, height: 0 });
  const mapping = computeContainMapping(imageWidth, imageHeight, layout.width, layout.height);

  return (
    <View
      style={styles.frame}
      testID="source-image-frame"
      onLayout={e => {
        const { width, height } = e.nativeEvent.layout;
        setLayout({ width, height });
      }}>
      <Image
        source={resolveImageAsset(assetKey)}
        style={[styles.image, { width: layout.width || '100%', height: layout.height || 320 }]}
        accessibilityLabel="Bundled synthetic demo source image"
      />
      {regions.map((region, index) => {
        const rect = mapBoundsToDisplay(region.bounds, mapping);
        const selected = region.id === selectedId;
        const overlayStyle = {
          left: rect.x,
          top: rect.y,
          width: rect.width,
          height: rect.height,
          borderColor: selected ? '#1a6fd4' : 'rgba(26, 111, 212, 0.45)',
          backgroundColor: selected ? 'rgba(26, 111, 212, 0.18)' : 'transparent',
          borderWidth: selected ? 3 : 1.5,
        };
        return (
          <Pressable
            key={region.id}
            testID={`region-${region.id}`}
            accessibilityLabel={`Select block ${index + 1}: ${region.label}`}
            onPress={() => onSelect(region.id)}
            style={[styles.region, overlayStyle]}>
            <Text style={[styles.regionTag, selected && styles.regionTagSelected]}>{index + 1}</Text>
          </Pressable>
        );
      })}
    </View>
  );
}

const styles = StyleSheet.create({
  frame: {
    backgroundColor: '#efe9da',
    borderColor: '#c9c2b2',
    borderRadius: 8,
    borderWidth: 1,
    height: 320,
    overflow: 'hidden',
    position: 'relative',
    width: '100%',
  },
  image: {
    resizeMode: 'contain',
  },
  region: {
    borderRadius: 2,
    position: 'absolute',
  },
  regionTag: {
    backgroundColor: 'rgba(26, 111, 212, 0.7)',
    borderRadius: 8,
    color: '#fff',
    fontSize: 11,
    fontWeight: '700',
    left: 2,
    paddingHorizontal: 6,
    paddingVertical: 1,
    position: 'absolute',
    top: 2,
  },
  regionTagSelected: {
    backgroundColor: '#1a6fd4',
  },
});
