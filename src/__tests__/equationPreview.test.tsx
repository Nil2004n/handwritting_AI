/** Local LaTeX-subset parser: supported constructs and clean failures. */
import React from 'react';
import { Text } from 'react-native';
import { act, create, ReactTestRenderer } from 'react-test-renderer';
import { EquationPreview, parseLatex } from '../components/EquationPreview';
import { controlsWithTestID, controlWithTestID } from '../testUtils/queries';

function render(latex: string): ReactTestRenderer {
  let tree: ReactTestRenderer | undefined;
  act(() => {
    tree = create(<EquationPreview latex={latex} />);
  });
  if (!tree) throw new Error('failed to render');
  return tree;
}

type AnyNode = { kind: string; num?: AnyNode[]; den?: AnyNode[]; body?: AnyNode[] };

function flatten(nodes: AnyNode[]): string[] {
  const out: string[] = [];
  for (const n of nodes) {
    out.push(n.kind);
    for (const child of [...(n.num ?? []), ...(n.den ?? []), ...(n.body ?? [])]) {
      out.push(...flatten([child]));
    }
  }
  return out;
}

test('parses fraction, superscript, subscript and square root', () => {
  const kinds = flatten(parseLatex('E = \\frac{1}{2} m v^2 + \\sqrt{b_1^2}') as AnyNode[]);
  for (const expected of ['frac', 'sup', 'sub', 'sqrt', 'text']) {
    expect(kinds).toContain(expected);
  }
});

test('renders every fixture equation without a preview error', () => {
  const samples = [
    'E = \\frac{1}{2} m v^2',
    '\\frac{x^2 + 3x + 2}{x + 1} = x + 2',
    'A = \\sqrt{b_1^2 + b_2^2}',
    '\\bar{x} = \\frac{8.5 + 9.0}{2}',
  ];
  for (const latex of samples) {
    const root = render(latex).root;
    expect(controlsWithTestID(root, 'equation-preview-error')).toHaveLength(0);
    expect(controlsWithTestID(root, 'equation-preview-ok').length).toBeGreaterThanOrEqual(1);
  }
});

test('shows a local error without crashing on unsupported or broken input', () => {
  for (const bad of ['\\integrate{x}', '\\frac{x}{y', 'x^']) {
    const root = render(bad).root;
    expect(controlsWithTestID(root, 'equation-preview-error').length).toBeGreaterThanOrEqual(1);
  }
  // Error text is human-readable.
  const root = render('\\integrate{x}').root;
  const texts = controlWithTestID(root, 'equation-preview-error')
    .findAllByType(Text)
    .map(t => (Array.isArray(t.props.children) ? t.props.children.join('') : String(t.props.children ?? '')));
  expect(texts.some(t => t.includes('Unsupported command'))).toBe(true);
});
