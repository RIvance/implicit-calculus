import { useMemo } from 'react';
import { Badge, Button, Group, Text, useComputedColorScheme } from '@mantine/core';
import {
  Background,
  Controls,
  Handle,
  MarkerType,
  MiniMap,
  Position,
  ReactFlow,
  type Edge,
  type Node,
  type NodeProps,
} from '@xyflow/react';
import dagre from '@dagrejs/dagre';
import { Braces, Layers, ScanSearch } from 'lucide-react';
import type { EvidenceSpine, GoalResult } from '../bridge/protocol';
import '@xyflow/react/dist/style.css';

type ProofNode = Node<{ spine: EvidenceSpine }, 'proof'>;

function ProofCard({ data, selected }: NodeProps<ProofNode>) {
  const { spine } = data;
  return (
    <div className={`proof-node ${spine.root ? 'proof-root' : ''} ${selected ? 'proof-selected' : ''}`}>
      <Handle type="target" position={Position.Top} />
      <div className="proof-node-top">
        <span>z{subscript(spine.index)}</span>
        <span>{spine.root ? 'GOAL' : 'EVIDENCE'}</span>
      </div>
      <strong>{spine.name}</strong>
      <div className="proof-node-type">{spine.type}</div>
      <div className="proof-node-arguments">
        {spine.arguments.length === 0 && <span>Δ reference</span>}
        {spine.arguments.map((argument, index) => (
          <span key={index} className={argument.kind}>
            {argument.kind === 'type' ? `[${argument.type}]` : `{z${subscript(argument.spine)}}`}
          </span>
        ))}
      </div>
      <Handle type="source" position={Position.Bottom} />
    </div>
  );
}

const nodeTypes = { proof: ProofCard };
const digits = '₀₁₂₃₄₅₆₇₈₉';
export function subscript(value: number) {
  return String(value).replace(/\d/g, (digit) => digits[Number(digit)]);
}

export function EvidenceGraph({
  goal,
  selected,
  onSelect,
  onConstraints,
}: {
  goal: GoalResult;
  selected: number | null;
  onSelect: (index: number | null) => void;
  onConstraints: (index: number) => void;
}) {
  const colorScheme = useComputedColorScheme('light');
  const evidence = goal.synthesis.evidence;
  const { nodes, edges } = useMemo(() => {
    if (!evidence) return { nodes: [], edges: [] };
    const graph = new dagre.graphlib.Graph()
      .setGraph({ rankdir: 'TB', nodesep: 52, ranksep: 84, marginx: 30, marginy: 20 })
      .setDefaultEdgeLabel(() => ({}));
    const edges: Edge[] = [];
    evidence.spines.forEach((spine) => graph.setNode(String(spine.index), { width: 250, height: 145 }));
    evidence.spines.forEach((spine) =>
      spine.arguments.forEach((argument, position) => {
        if (argument.kind === 'implicit') {
          graph.setEdge(String(argument.spine), String(spine.index));
          edges.push({
            id: `${argument.spine}-${spine.index}-${position}`,
            source: String(argument.spine),
            target: String(spine.index),
            label: `argument ${position + 1}`,
            type: 'smoothstep',
            markerEnd: { type: MarkerType.ArrowClosed },
            animated: selected === spine.index || selected === argument.spine,
            style: { stroke: '#548a99', strokeWidth: 1.7 },
            labelStyle: { fill: '#64748b', fontSize: 11 },
          });
        }
      }),
    );
    dagre.layout(graph);
    const nodes: ProofNode[] = evidence.spines.map((spine) => {
      const position = graph.node(String(spine.index));
      return {
        id: String(spine.index),
        type: 'proof',
        data: { spine },
        position: { x: position.x - 125, y: position.y - 72.5 },
        selected: selected === spine.index,
      };
    });
    return { nodes, edges };
  }, [evidence, selected]);

  if (!evidence)
    return (
      <div className="empty-state">
        <ScanSearch size={30} />
        <h3>No accepted evidence</h3>
        <p>{goal.synthesis.diagnostic?.message ?? 'The search is still running.'}</p>
        {goal.synthesis.attempts.length > 0 && (
          <p>Inspect the search attempts to see the constraints and provisional models.</p>
        )}
      </div>
    );

  const active = evidence.spines.find((spine) => spine.index === selected);
  return (
    <div className="evidence-view">
      <div className="graph-toolbar">
        <Group gap="xs">
          <Badge variant="light" color="teal">
            {evidence.spines.length} spines
          </Badge>
          <Badge variant="light" color="gray">
            cost {evidence.cost}
          </Badge>
        </Group>
        <span>Click a node to inspect its construction</span>
      </div>
      <div className="proof-canvas" data-testid="evidence-graph">
        <ReactFlow<ProofNode>
          key={goal.request.type + evidence.spines.length}
          nodes={nodes}
          edges={edges}
          nodeTypes={nodeTypes}
          onNodeClick={(_, node) => onSelect(node.data.spine.index)}
          onPaneClick={() => onSelect(null)}
          fitView
          fitViewOptions={{ padding: 0.25, maxZoom: 1 }}
          minZoom={0.2}
          maxZoom={1.8}
          nodesDraggable={false}
          nodesConnectable={false}
          colorMode={colorScheme}
          aria-label="Synthesized evidence graph"
        >
          <Background gap={20} size={1} color="#94a3b835" />
          <Controls showInteractive={false} />
          <MiniMap
            pannable
            zoomable
            nodeColor={(node) => ((node.data.spine as EvidenceSpine).root ? '#1b9688' : '#6b8ca3')}
          />
        </ReactFlow>
      </div>
      {active ? (
        <div className="node-details">
          <Group justify="space-between" mb="xs">
            <Group gap="xs">
              <Layers size={16} />
              <Text fw={650}>Spine z{subscript(active.index)}</Text>
            </Group>
            <Button variant="subtle" size="compact-xs" onClick={() => onConstraints(active.index)}>
              Inspect constraints
            </Button>
          </Group>
          <p>
            Head <code>{active.name}</code> is instance Δ[{active.head}].
          </p>
          <pre className="math-block">
            {active.term} : {active.type}
          </pre>
          {active.arguments.map((argument, index) => (
            <div className="argument-row" key={index}>
              <Badge size="xs" color={argument.kind === 'type' ? 'blue' : 'teal'} variant="light">
                {index + 1}
              </Badge>
              {argument.kind === 'type' ? (
                <span>
                  Instantiate with <code>{argument.type}</code>
                </span>
              ) : (
                <button onClick={() => onSelect(argument.spine)}>
                  Supply evidence from z{subscript(argument.spine)}
                </button>
              )}
            </div>
          ))}
        </div>
      ) : (
        <div className="evidence-term">
          <Braces size={16} />
          <code>{goal.synthesis.term}</code>
        </div>
      )}
    </div>
  );
}
