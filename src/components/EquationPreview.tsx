/**
 * Minimal local LaTeX-subset preview (Phase 0).
 *
 * Fully offline: renders with plain React Native Text/View, no WebView, no
 * CDN, no native setup. Deliberately small — it covers exactly what the
 * fixtures need, nothing more:
 *   \frac{a}{b}, \sqrt{x}, x^{2} / x^2, x_{1} / x_1, \bar{x},
 *   \pm \times \cdot \leq \geq \neq \( \) \[ \]
 * Anything else surfaces a readable local error (never a crash, and the
 * user's LaTeX input is never discarded).
 *
 * This is a PREVIEW of hand-typed/demo LaTeX, not equation recognition.
 */

import React from 'react';
import { StyleSheet, Text, View } from 'react-native';

export class LatexRenderError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'LatexRenderError';
  }
}

type Node =
  | { kind: 'text'; value: string }
  | { kind: 'frac'; num: Node[]; den: Node[] }
  | { kind: 'sqrt'; body: Node[] }
  | { kind: 'bar'; body: Node[] }
  | { kind: 'sup'; body: Node[] }
  | { kind: 'sub'; body: Node[] };

const SYMBOLS: Record<string, string> = {
  pm: '±',
  times: '×',
  cdot: '·',
  leq: '≤',
  geq: '≥',
  neq: '≠',
  '(': '(',
  ')': ')',
  '[': '[',
  ']': ']',
  '{': '{',
  '}': '}',
  '\\': '\\',
};

class Parser {
  private i = 0;
  constructor(private readonly src: string) {}

  parse(): Node[] {
    const nodes = this.parseSequence(null);
    if (this.i < this.src.length) {
      throw new LatexRenderError(`Unexpected character "${this.src[this.i]}" at position ${this.i}.`);
    }
    return nodes;
  }

  private parseSequence(stopAt: string | null): Node[] {
    const nodes: Node[] = [];
    let text = '';
    const flush = () => {
      if (text) {
        nodes.push({ kind: 'text', value: text });
        text = '';
      }
    };
    while (this.i < this.src.length) {
      const ch = this.src[this.i];
      if (stopAt && ch === stopAt) {
        flush();
        this.i++; // consume closer
        return nodes;
      }
      if (ch === '{') {
        this.i++;
        flush();
        nodes.push(...this.parseSequence('}'));
      } else if (ch === '}') {
        throw new LatexRenderError(`Unbalanced "}" at position ${this.i}.`);
      } else if (ch === '\\') {
        flush();
        nodes.push(...this.parseCommand());
      } else if (ch === '^' || ch === '_') {
        this.i++;
        flush();
        const body = this.parseScriptBody(ch);
        nodes.push(ch === '^' ? { kind: 'sup', body } : { kind: 'sub', body });
      } else {
        text += ch;
        this.i++;
      }
    }
    flush();
    if (stopAt) {
      throw new LatexRenderError(`Missing closing "${stopAt}" — check brace balance.`);
    }
    return nodes;
  }

  private parseScriptBody(op: string): Node[] {
    if (this.i >= this.src.length) {
      throw new LatexRenderError(`"${op}" at the end of input needs a value, e.g. ${op}{2}.`);
    }
    if (this.src[this.i] === '{') {
      this.i++;
      return this.parseSequence('}');
    }
    const ch = this.src[this.i++];
    return [{ kind: 'text', value: ch }];
  }

  private parseCommand(): Node[] {
    const start = this.i;
    this.i++; // consume backslash
    let name = '';
    if (this.i < this.src.length && !/[a-zA-Z]/.test(this.src[this.i])) {
      name = this.src[this.i++];
    } else {
      while (this.i < this.src.length && /[a-zA-Z]/.test(this.src[this.i])) {
        name += this.src[this.i++];
      }
    }
    if (name === 'frac') {
      return [{ kind: 'frac', num: this.parseGroup('frac numerator'), den: this.parseGroup('frac denominator') }];
    }
    if (name === 'sqrt') {
      return [{ kind: 'sqrt', body: this.parseGroup('sqrt') }];
    }
    if (name === 'bar') {
      return [{ kind: 'bar', body: this.parseGroup('bar') }];
    }
    if (name in SYMBOLS) {
      return [{ kind: 'text', value: SYMBOLS[name] }];
    }
    throw new LatexRenderError(
      `Unsupported command "\\${name}" at position ${start}. Supported: \\frac, \\sqrt, \\bar, ^, _, \\pm, \\times, \\cdot, \\leq, \\geq, \\neq.`,
    );
  }

  private parseGroup(what: string): Node[] {
    if (this.src[this.i] !== '{') {
      throw new LatexRenderError(`Expected "{...}" for ${what} at position ${this.i}.`);
    }
    this.i++;
    return this.parseSequence('}');
  }
}

export function parseLatex(src: string): Node[] {
  return new Parser(src).parse();
}

const BASE_SIZE = 20;

function renderNodes(nodes: Node[], keyPrefix: string, size: number): React.ReactNode {
  return nodes.map((n, i) => renderNode(n, `${keyPrefix}-${i}`, size));
}

function renderNode(node: Node, key: string, size: number): React.ReactNode {
  switch (node.kind) {
    case 'text':
      return (
        <Text key={key} style={{ fontSize: size }}>
          {node.value}
        </Text>
      );
    case 'frac':
      return (
        <View key={key} style={styles.frac}>
          <View style={styles.fracPart}>{renderNodes(node.num, `${key}n`, size * 0.85)}</View>
          <View style={styles.fracLine} />
          <View style={styles.fracPart}>{renderNodes(node.den, `${key}d`, size * 0.85)}</View>
        </View>
      );
    case 'sqrt':
      return (
        <View key={key} style={styles.row}>
          <Text style={{ fontSize: size }}>√</Text>
          <View style={styles.sqrtBody}>
            {renderNodes(node.body, `${key}s`, size)}
          </View>
        </View>
      );
    case 'bar':
      return (
        <View key={key} style={styles.barBody}>
          {renderNodes(node.body, `${key}b`, size)}
        </View>
      );
    case 'sup':
      return (
        <Text key={key} style={{ fontSize: size * 0.68 }}>
          {renderNodes(node.body, `${key}p`, size * 0.68)}
        </Text>
      );
    case 'sub':
      return (
        <Text key={key} style={{ fontSize: size * 0.68 }}>
          {renderNodes(node.body, `${key}s`, size * 0.68)}
        </Text>
      );
  }
}

export function EquationPreview({ latex }: { latex: string }) {
  let body: React.ReactNode;
  try {
    const nodes = parseLatex(latex);
    body = (
      <View style={styles.row} testID="equation-preview-ok">
        {renderNodes(nodes, 'root', BASE_SIZE)}
      </View>
    );
  } catch (err) {
    const message = err instanceof LatexRenderError ? err.message : 'Could not render this equation.';
    body = (
      <View style={styles.errorBox} testID="equation-preview-error">
        <Text style={styles.errorTitle}>Preview unavailable for this input</Text>
        <Text style={styles.errorText}>{message}</Text>
        <Text style={styles.errorText}>Your LaTeX above is unchanged — edit it to fix the preview.</Text>
      </View>
    );
  }
  return (
    <View style={styles.container} accessible accessibilityLabel="Equation preview">
      {body}
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    backgroundColor: '#ffffff',
    borderColor: '#c9c2b2',
    borderRadius: 8,
    borderWidth: 1,
    minHeight: 56,
    padding: 12,
  },
  row: {
    alignItems: 'center',
    flexDirection: 'row',
    flexWrap: 'wrap',
  },
  frac: {
    alignItems: 'stretch',
    marginHorizontal: 4,
  },
  fracPart: {
    alignItems: 'center',
    flexDirection: 'row',
    justifyContent: 'center',
  },
  fracLine: {
    borderColor: '#1c1c1c',
    borderTopWidth: 1.5,
    marginVertical: 2,
  },
  sqrtBody: {
    borderColor: '#1c1c1c',
    borderTopWidth: 1.5,
    flexDirection: 'row',
    paddingLeft: 2,
  },
  barBody: {
    borderColor: '#1c1c1c',
    borderTopWidth: 1.5,
    flexDirection: 'row',
  },
  errorBox: {
    gap: 4,
  },
  errorTitle: {
    color: '#8a3b00',
    fontWeight: '600',
  },
  errorText: {
    color: '#5b5347',
  },
});
