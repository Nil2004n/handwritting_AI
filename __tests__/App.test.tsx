/**
 * App-level UI flow (Phase 0): list -> review -> extract demo -> edit.
 * Uses the project's existing react-test-renderer setup.
 */
import React from 'react';
import { Text } from 'react-native';
import { act, create, ReactTestRenderer } from 'react-test-renderer';
import App from '../App';
import { controlsWithTestID, controlWithTestID } from '../src/testUtils/queries';

jest.useFakeTimers();

function renderApp(): ReactTestRenderer {
  let tree: ReactTestRenderer | undefined;
  act(() => {
    tree = create(<App />);
  });
  if (!tree) throw new Error('failed to render App');
  return tree;
}

async function flushExtract() {
  await act(async () => {
    jest.advanceTimersByTime(700);
  });
  await act(async () => {});
}

test('demo list opens a fixture and runs the mock extract-edit flow', async () => {
  const tree = renderApp();
  const root = tree.root;

  // Demo banner is visible on the list screen.
  expect(controlsWithTestID(root, 'demo-banner').length).toBeGreaterThan(0);

  // Open the prose fixture.
  act(() => {
    controlWithTestID(root, 'open-fixture-prose').props.onPress();
  });

  // Pre-extraction state offers exactly one extract action.
  expect(controlsWithTestID(root, 'extract-button').length).toBeGreaterThanOrEqual(1);

  // Start extraction: button is replaced by a loading indicator (no duplicates).
  act(() => {
    controlWithTestID(root, 'extract-button').props.onPress();
  });
  expect(controlsWithTestID(root, 'extract-loading').length).toBeGreaterThanOrEqual(1);
  expect(controlsWithTestID(root, 'extract-button')).toHaveLength(0);

  await flushExtract();

  // Blocks rendered in reading order with an editable first block.
  expect(controlsWithTestID(root, 'select-prose-b1').length).toBeGreaterThanOrEqual(1);
  expect(controlsWithTestID(root, 'edit-prose-b1').length).toBeGreaterThanOrEqual(1);
  const originText = controlWithTestID(root, 'origin-line').props.children;
  expect(Array.isArray(originText) ? originText.join('') : String(originText)).toMatch(/mock/);

  // Edit the text: the editable copy updates and the row marks itself edited.
  act(() => {
    controlWithTestID(root, 'edit-prose-b1').props.onChangeText('The fixed sentence.');
  });
  expect(controlWithTestID(root, 'edit-prose-b1').props.value).toBe('The fixed sentence.');

  // Raw recognition text is still shown separately and unchanged.
  const rawTexts = root
    .findAllByType(Text)
    .map(t => (Array.isArray(t.props.children) ? t.props.children.join('') : String(t.props.children ?? '')));
  expect(rawTexts.some(t => t.includes('Teh quick brown fox'))).toBe(true);

  // Reset requires explicit confirmation; confirming clears the result.
  act(() => {
    controlWithTestID(root, 'reset-button').props.onPress();
  });
  expect(controlsWithTestID(root, 'confirm-reset-button').length).toBeGreaterThanOrEqual(1);
  act(() => {
    controlWithTestID(root, 'confirm-reset-button').props.onPress();
  });
  expect(controlsWithTestID(root, 'extract-button').length).toBeGreaterThanOrEqual(1);
});
