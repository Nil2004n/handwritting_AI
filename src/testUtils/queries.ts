/**
 * Test helpers for react-test-renderer + the RN jest preset.
 *
 * The preset's mocked components forward props (including testID) to inner
 * host elements, so a raw `findAllByProps({ testID })` matches several
 * nodes. Interaction props (onPress, onChangeText, value) live on the
 * composite component node, so these helpers query composites only.
 */
import type { ReactTestInstance } from 'react-test-renderer';

export function controlsWithTestID(root: ReactTestInstance, testID: string): ReactTestInstance[] {
  return root.findAll(
    node => typeof node.type !== 'string' && (node.props as { testID?: string } | null)?.testID === testID,
  );
}

export function controlWithTestID(root: ReactTestInstance, testID: string): ReactTestInstance {
  const matches = controlsWithTestID(root, testID);
  if (matches.length === 0) {
    throw new Error(`No component with testID "${testID}"`);
  }
  return matches[0];
}
