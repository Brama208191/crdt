import { Editor, Transforms, type BaseEditor, type Descendant, type Point } from 'slate';
import { HistoryEditor } from 'slate-history';
import type { ReactEditor } from 'slate-react';

// The CRDT holds plain text; Slate shows it as one paragraph per line.

type Paragraph = { type: 'paragraph'; children: { text: string }[] };

declare module 'slate' {
  interface CustomTypes {
    Editor: BaseEditor & ReactEditor & HistoryEditor;
    Element: Paragraph;
    Text: { text: string };
  }
}

export function toSlate(text: string): Descendant[] {
  return text.split('\n').map((line) => ({ type: 'paragraph', children: [{ text: line }] }));
}

export function editorText(editor: Editor): string {
  return editor.children.map((_, i) => Editor.string(editor, [i])).join('\n');
}

export function pointToOffset(editor: Editor, point: Point): number {
  let offset = 0;
  for (let i = 0; i < point.path[0]; i++) offset += Editor.string(editor, [i]).length + 1;
  return offset + point.offset;
}

function offsetToPoint(text: string, offset: number): Point {
  const lines = text.slice(0, offset).split('\n');
  return { path: [lines.length - 1, 0], offset: lines[lines.length - 1].length };
}

/**
 * Replaces the editor's content without touching undo history. `selection` is given as absolute
 * text offsets, already shifted by the caller for the remote edit.
 */
export function setEditorText(editor: Editor, text: string, selection: { anchor: number; focus: number } | null) {
  HistoryEditor.withoutSaving(editor, () => {
    Editor.withoutNormalizing(editor, () => {
      for (let i = editor.children.length - 1; i >= 0; i--) Transforms.removeNodes(editor, { at: [i] });
      Transforms.insertNodes(editor, toSlate(text), { at: [0] });
    });
    if (selection) {
      Transforms.select(editor, {
        anchor: offsetToPoint(text, Math.min(selection.anchor, text.length)),
        focus: offsetToPoint(text, Math.min(selection.focus, text.length)),
      });
    }
  });
  // Old undo entries point at paths that no longer exist after a rebuild; replaying them would
  // corrupt the text. Undo stays scoped to your own edits since the last remote change.
  editor.history = { undos: [], redos: [] };
}
