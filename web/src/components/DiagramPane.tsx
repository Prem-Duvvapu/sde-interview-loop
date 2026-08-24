import { useCallback, useEffect, useMemo, useRef, useState } from 'react';

/**
 * The HLD work surface: a structured component graph (DM-2), not a drawing tool.
 *
 * The candidate edits nodes and flows as data; the pane serialises the graph to JSON on
 * every change and hands it to the parent through `onBufferChange` — the same ref-based
 * channel Monaco uses — so the backend and the model always receive a machine-readable
 * `{nodes, edges}` document they can reason over by component name. The SVG preview is
 * derived, deterministic (column-per-tier layout) and never stored.
 */

type NodeKind =
  | 'client'
  | 'gateway'
  | 'service'
  | 'cache'
  | 'queue'
  | 'database'
  | 'storage'
  | 'external';

interface DesignNode {
  id: string;
  label: string;
  type: NodeKind;
}

interface DesignEdge {
  from: string;
  to: string;
  label: string;
}

const NODE_KINDS: { id: NodeKind; label: string }[] = [
  { id: 'client', label: 'Client' },
  { id: 'gateway', label: 'Gateway / LB' },
  { id: 'service', label: 'Service' },
  { id: 'cache', label: 'Cache' },
  { id: 'queue', label: 'Queue' },
  { id: 'database', label: 'Database' },
  { id: 'storage', label: 'Object storage' },
  { id: 'external', label: 'External' },
];

/** Left-to-right tier order for the derived preview. */
const KIND_COLUMN: Record<NodeKind, number> = {
  client: 0,
  gateway: 1,
  service: 2,
  cache: 3,
  queue: 3,
  database: 4,
  storage: 4,
  external: 5,
};

const KIND_COLOR: Record<NodeKind, string> = {
  client: '#8b93a3',
  gateway: '#7fb6ff',
  service: '#45b39d',
  cache: '#e0b177',
  queue: '#c99de0',
  database: '#62d3bb',
  storage: '#5cbf87',
  external: '#e06a52',
};

function emptyGraph(): string {
  return JSON.stringify({ nodes: [], edges: [] }, null, 2);
}

interface Props {
  title: string;
  onBufferChange: (value: string) => void;
  readOnly?: boolean;
  /** Bumped by the parent to force the starter graph back in (new round, reset). */
  resetToken: number;
}

export function DiagramPane({ title, onBufferChange, readOnly = false, resetToken }: Props) {
  const [nodes, setNodes] = useState<DesignNode[]>([]);
  const [edges, setEdges] = useState<DesignEdge[]>([]);
  const nextIdRef = useRef(1);

  const [newLabel, setNewLabel] = useState('');
  const [newType, setNewType] = useState<NodeKind>('service');
  const [edgeFrom, setEdgeFrom] = useState('');
  const [edgeTo, setEdgeTo] = useState('');
  const [edgeLabel, setEdgeLabel] = useState('');
  const [graphError, setGraphError] = useState<string | null>(null);

  // Publish the serialised graph upward. Same contract as EditorPane: cheap enough to
  // run on every discrete edit, since graph edits are clicks, not keystrokes.
  useEffect(() => {
    const json = JSON.stringify({ nodes, edges }, null, 2);
    onBufferChange(json);
  }, [nodes, edges, onBufferChange]);

  // Initial publish once mounted.
  useEffect(() => {
    onBufferChange(emptyGraph());
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    if (resetToken === 0) return;
    setNodes([]);
    setEdges([]);
    nextIdRef.current = 1;
    setEdgeFrom('');
    setEdgeTo('');
    setEdgeLabel('');
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [resetToken]);

  const addNode = useCallback(() => {
    const label = newLabel.trim();
    if (!label || readOnly) return;
    if (label.length > 40) {
      setGraphError('Component names stay under 40 characters.');
      return;
    }
    setGraphError(null);
    const id = `n${nextIdRef.current++}`;
    setNodes((prev) => [...prev, { id, label, type: newType }]);
    setNewLabel('');
  }, [newLabel, newType, readOnly]);

  const removeNode = useCallback(
    (id: string) => {
      if (readOnly) return;
      setNodes((prev) => prev.filter((n) => n.id !== id));
      setEdges((prev) => prev.filter((e) => e.from !== id && e.to !== id));
      setEdgeFrom((f) => (f === id ? '' : f));
      setEdgeTo((t) => (t === id ? '' : t));
    },
    [readOnly],
  );

  const addEdge = useCallback(() => {
    if (readOnly || !edgeFrom || !edgeTo) return;
    if (edgeFrom === edgeTo) {
      setGraphError('A flow needs two different components.');
      return;
    }
    const existing = edges.find((e) => e.from === edgeFrom && e.to === edgeTo);
    if (existing && !edgeLabel.trim() && !existing.label) {
      setGraphError('That flow already exists.');
      return;
    }
    setGraphError(null);
    const label = edgeLabel.trim();
    setEdges((prev) =>
      existing
        ? prev.map((e) => (e === existing ? { ...e, label } : e))
        : [...prev, { from: edgeFrom, to: edgeTo, label }],
    );
    setEdgeLabel('');
  }, [readOnly, edgeFrom, edgeTo, edgeLabel, edges]);

  const removeEdge = useCallback(
    (index: number) => {
      if (readOnly) return;
      setEdges((prev) => prev.filter((_, i) => i !== index));
    },
    [readOnly],
  );

  // ------------------------------------------------------------- derived layout

  const layout = useMemo(() => {
    const NODE_W = 118;
    const NODE_H = 30;
    const COL_GAP = 56;
    const ROW_GAP = 14;

    const columns = new Map<number, DesignNode[]>();
    for (const node of nodes) {
      const col = KIND_COLUMN[node.type] ?? 3;
      if (!columns.has(col)) columns.set(col, []);
      columns.get(col)!.push(node);
    }
    const colIndices = [...columns.keys()].sort((a, b) => a - b);
    const positions = new Map<string, { x: number; y: number }>();
    let maxRows = 0;
    colIndices.forEach((col, slotIndex) => {
      const columnNodes = columns.get(col)!;
      maxRows = Math.max(maxRows, columnNodes.length);
      columnNodes.forEach((node, row) => {
        positions.set(node.id, {
          x: slotIndex * (NODE_W + COL_GAP),
          y: row * (NODE_H + ROW_GAP),
        });
      });
    });

    const height = maxRows > 0 ? maxRows * (NODE_H + ROW_GAP) - ROW_GAP : NODE_H;
    const width =
      colIndices.length > 0
        ? colIndices.length * (NODE_W + COL_GAP) - COL_GAP
        : NODE_W;

    return { positions, nodeW: NODE_W, nodeH: NODE_H, width, height };
  }, [nodes]);

  const nodeById = useMemo(() => new Map(nodes.map((n) => [n.id, n])), [nodes]);
  const canAddEdge = edgeFrom !== '' && edgeTo !== '' && edgeFrom !== edgeTo;

  return (
    <section className="pane editor-pane diagram-pane" aria-label={title}>
      <div className="pane-head">
        <h2>{title}</h2>
        <span className="pane-sub" title="Serialised as a structured graph and sent with your next turn">
          {nodes.length} components · {edges.length} flows
        </span>
      </div>

      <div className="diagram-body">
        <svg
          className="diagram-canvas"
          viewBox={`0 0 ${layout.width} ${layout.height}`}
          preserveAspectRatio="xMidYMid meet"
          role="img"
          aria-label="Design graph preview"
        >
          <defs>
            <marker id="diagram-arrow" viewBox="0 0 10 10" refX="9" refY="5"
              markerWidth="7" markerHeight="7" orient="auto-start-reverse">
              <path d="M 0 0 L 10 5 L 0 10 z" fill="#5d6675" />
            </marker>
          </defs>

          {edges.map((edge, i) => {
            const a = layout.positions.get(edge.from);
            const b = layout.positions.get(edge.to);
            if (!a || !b) return null;
            const x1 = a.x + layout.nodeW;
            const y1 = a.y + layout.nodeH / 2;
            const x2 = b.x;
            const y2 = b.y + layout.nodeH / 2;
            const backward = x2 <= x1;
            const mx = backward ? Math.min(x1, x2) - 26 : (x1 + x2) / 2;
            const d = backward
              ? `M ${x1} ${y1} C ${mx} ${y1}, ${mx} ${y2 + 34}, ${x2} ${y2}`
              : `M ${x1} ${y1} C ${mx} ${y1}, ${mx} ${y2}, ${x2} ${y2}`;
            return (
              <g key={`${edge.from}-${edge.to}-${i}`}>
                <path d={d} fill="none" stroke="#39414f" strokeWidth={1.4}
                  markerEnd="url(#diagram-arrow)" />
                {edge.label && (
                  <text x={(x1 + x2) / 2} y={(y1 + y2) / 2 - 5}
                    className="diagram-edge-label" textAnchor="middle">
                    {edge.label}
                  </text>
                )}
              </g>
            );
          })}

          {nodes.map((node) => {
            const p = layout.positions.get(node.id);
            if (!p) return null;
            return (
              <g key={node.id} className="diagram-node">
                <rect x={p.x} y={p.y} width={layout.nodeW} height={layout.nodeH}
                  rx={6} fill="#191d25" stroke={KIND_COLOR[node.type]} strokeWidth={1.3} />
                <circle cx={p.x + 11} cy={p.y + layout.nodeH / 2} r={3.4}
                  fill={KIND_COLOR[node.type]} />
                <text x={p.x + 21} y={p.y + layout.nodeH / 2 + 3.6} className="diagram-node-label">
                  {node.label.length > 15 ? `${node.label.slice(0, 14)}…` : node.label}
                </text>
              </g>
            );
          })}

          {nodes.length === 0 && (
            <text x={layout.width / 2} y={layout.nodeH} className="diagram-empty" textAnchor="middle">
              Add your first component below — the interviewer reads this graph.
            </text>
          )}
        </svg>

        {!readOnly && (
          <div className="diagram-editor">
            <div className="diagram-row">
              <input
                type="text"
                value={newLabel}
                placeholder="Component name (e.g. ReadCache)"
                maxLength={60}
                onChange={(e) => setNewLabel(e.target.value)}
                onKeyDown={(e) => e.key === 'Enter' && addNode()}
                aria-label="New component name"
              />
              <select value={newType} onChange={(e) => setNewType(e.target.value as NodeKind)}
                aria-label="Component type">
                {NODE_KINDS.map((k) => (
                  <option key={k.id} value={k.id}>{k.label}</option>
                ))}
              </select>
              <button type="button" onClick={addNode} disabled={!newLabel.trim()}>
                Add
              </button>
            </div>

            <div className="diagram-row">
              <select value={edgeFrom} onChange={(e) => setEdgeFrom(e.target.value)}
                aria-label="Flow source" disabled={nodes.length < 1}>
                <option value="">From…</option>
                {nodes.map((n) => (
                  <option key={n.id} value={n.id}>{n.label}</option>
                ))}
              </select>
              <select value={edgeTo} onChange={(e) => setEdgeTo(e.target.value)}
                aria-label="Flow target" disabled={nodes.length < 1}>
                <option value="">To…</option>
                {nodes.map((n) => (
                  <option key={n.id} value={n.id}>{n.label}</option>
                ))}
              </select>
              <input
                type="text"
                value={edgeLabel}
                placeholder="Label (reads, fan-out…)"
                maxLength={24}
                onChange={(e) => setEdgeLabel(e.target.value)}
                onKeyDown={(e) => e.key === 'Enter' && addEdge()}
                disabled={!canAddEdge}
                aria-label="Flow label"
              />
              <button type="button" onClick={addEdge} disabled={!canAddEdge}>
                Add flow
              </button>
            </div>

            {graphError && <p className="diagram-error">{graphError}</p>}

            {(nodes.length > 0 || edges.length > 0) && (
              <ul className="diagram-list">
                {nodes.map((n) => (
                  <li key={n.id}>
                    <span className="diagram-dot" style={{ background: KIND_COLOR[n.type] }} />
                    <span className="diagram-list-label">{n.label}</span>
                    <span className="diagram-list-kind">{NODE_KINDS.find((k) => k.id === n.type)?.label}</span>
                    <button type="button" className="link-btn diagram-remove"
                      onClick={() => removeNode(n.id)} aria-label={`Remove ${n.label}`}>
                      remove
                    </button>
                  </li>
                ))}
                {edges.map((e, i) => (
                  <li key={`e-${i}`} className="diagram-edge-row">
                    <span className="diagram-dot diagram-dot-edge" />
                    <span className="diagram-list-label">
                      {nodeById.get(e.from)?.label ?? '?'} → {nodeById.get(e.to)?.label ?? '?'}
                      {e.label && <em> · {e.label}</em>}
                    </span>
                    <button type="button" className="link-btn diagram-remove"
                      onClick={() => removeEdge(i)} aria-label="Remove flow">
                      remove
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </div>
        )}
      </div>
    </section>
  );
}
