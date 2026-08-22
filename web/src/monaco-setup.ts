import * as monaco from 'monaco-editor';
import editorWorker from 'monaco-editor/esm/vs/editor/editor.worker?worker';
import { loader } from '@monaco-editor/react';

/**
 * Monaco is bundled locally rather than pulled from a CDN at runtime.
 *
 * `@monaco-editor/react` defaults to loading Monaco from jsDelivr, which would make a
 * single-user local-first tool depend on the network to render its editor. Pointing the
 * loader at the bundled copy removes that.
 *
 * Only the core editor worker is wired up. The languages offered in the editor toolbar
 * (java, python, go, cpp, sql, markdown, plaintext) are tokenizer-only "basic languages"
 * with no background language service, so no other worker is needed — which is also what
 * keeps the bundle from growing by several more megabytes.
 */
declare global {
  interface Window {
    MonacoEnvironment?: monaco.Environment;
  }
}

window.MonacoEnvironment = {
  getWorker: () => new editorWorker(),
};

/** Editor theme tuned to the application palette so the code pane does not read as a foreign widget. */
export const MONACO_THEME = 'interview-loop-dark';

monaco.editor.defineTheme(MONACO_THEME, {
  base: 'vs-dark',
  inherit: true,
  rules: [
    { token: 'comment', foreground: '5d6675', fontStyle: 'italic' },
    { token: 'keyword', foreground: '7fb6ff' },
    { token: 'string', foreground: 'b8d99a' },
    { token: 'number', foreground: 'e0b177' },
    { token: 'type', foreground: '6fd6c3' },
    { token: 'delimiter', foreground: '8b93a3' },
  ],
  colors: {
    'editor.background': '#14171d',
    'editor.foreground': '#d7dbe2',
    'editorLineNumber.foreground': '#454d5c',
    'editorLineNumber.activeForeground': '#8b93a3',
    'editor.selectionBackground': '#2b4d55',
    'editor.lineHighlightBackground': '#191d25',
    'editorCursor.foreground': '#45b39d',
    'editorIndentGuide.background1': '#232833',
    'editorIndentGuide.activeBackground1': '#39414f',
    'editorWidget.background': '#191d25',
    'editorWidget.border': '#242a34',
    'scrollbarSlider.background': '#2a303b80',
    'scrollbarSlider.hoverBackground': '#39414fcc',
  },
});

loader.config({ monaco });

export interface EditorLanguage {
  id: string;
  label: string;
}

export const EDITOR_LANGUAGES: EditorLanguage[] = [
  { id: 'java', label: 'Java' },
  { id: 'python', label: 'Python' },
  { id: 'go', label: 'Go' },
  { id: 'cpp', label: 'C++' },
  { id: 'sql', label: 'SQL' },
  { id: 'markdown', label: 'Markdown' },
  { id: 'plaintext', label: 'Plain text' },
];

export const STARTER_BUFFERS: Record<string, string> = {
  java: `class Solution {\n\n    // Talk through the approach before you start typing.\n\n}\n`,
  python: `def solve():\n    # Talk through the approach before you start typing.\n    pass\n`,
  go: `package main\n\nfunc solve() {\n\t// Talk through the approach before you start typing.\n}\n`,
  cpp: `class Solution {\npublic:\n    // Talk through the approach before you start typing.\n};\n`,
  sql: `-- Talk through the approach before you start typing.\n`,
  markdown: `# Design\n\n## Requirements\n\n## Estimates\n\n## Components\n\n## Bottlenecks\n`,
  plaintext: '',
};
