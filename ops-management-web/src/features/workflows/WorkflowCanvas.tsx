import type { ReactNode } from "react";
import {
  Background,
  BackgroundVariant,
  MarkerType,
  Panel,
  Position,
  ReactFlow,
  ReactFlowProvider,
  useReactFlow,
  type Connection,
  type Edge,
  type Node,
} from "@xyflow/react";
import { Maximize2, ZoomIn, ZoomOut } from "lucide-react";
import type {
  WorkflowDraft,
  WorkflowNodeCatalogItem,
} from "../../api/workflow-types";
import "@xyflow/react/dist/style.css";

type CanvasNodeData = { label: ReactNode };

export function WorkflowCanvas({
  catalog,
  draft,
  selectedNodeId,
  onConnect,
  onDeleteNode,
  onDeleteTransition,
  onMoveNode,
  onSelectNode,
}: {
  catalog: WorkflowNodeCatalogItem[];
  draft: WorkflowDraft;
  selectedNodeId: string | null;
  onConnect: (fromNodeId: string, toNodeId: string) => void;
  onDeleteNode: (nodeId: string) => void;
  onDeleteTransition: (transitionId: string) => void;
  onMoveNode: (nodeId: string, position: { x: number; y: number }) => void;
  onSelectNode: (nodeId: string | null) => void;
}) {
  const catalogByType = new Map(catalog.map((item) => [item.type, item]));
  const nodes: Node<CanvasNodeData>[] = draft.nodes.map((node, index) => {
    const item = catalogByType.get(node.type);
    const fixed = node.type === "start" || node.type === "complete";
    return {
      id: node.nodeId,
      ariaLabel: `${item?.label ?? node.type} ${node.nodeId}`,
      className: `workflow-canvas-node node-category-${item?.category ?? "flow"}`,
      data: {
        label: (
          <div className="canvas-node-label">
            <strong>{item?.label ?? node.type}</strong>
            <small>{node.nodeId}</small>
          </div>
        ),
      },
      deletable: !fixed,
      position: node.position ?? { x: 120 + index * 220, y: 180 },
      selected: node.nodeId === selectedNodeId,
      sourcePosition: Position.Right,
      targetPosition: Position.Left,
      type: node.type === "start" ? "input" : node.type === "complete" ? "output" : "default",
    };
  });
  const edges: Edge[] = draft.transitions.map((transition) => ({
    id: transition.transitionId,
    source: transition.fromNodeId,
    target: transition.toNodeId,
    markerEnd: { type: MarkerType.ArrowClosed },
    className: "workflow-canvas-edge",
  }));

  function connect(connection: Connection) {
    if (connection.source && connection.target) {
      onConnect(connection.source, connection.target);
    }
  }

  return (
    <section aria-label="工作流画布" className="workflow-canvas" role="region">
      <ReactFlowProvider>
        <ReactFlow
          deleteKeyCode={["Backspace", "Delete"]}
          edges={edges}
          fitView
          fitViewOptions={{ padding: 0.25, maxZoom: 1.15 }}
          minZoom={0.35}
          nodes={nodes}
          nodesConnectable
          nodesDraggable
          onConnect={connect}
          onEdgesDelete={(items) => items.forEach((item) => onDeleteTransition(item.id))}
          onNodeClick={(_, node) => onSelectNode(node.id)}
          onNodeDragStop={(_, node) => onMoveNode(node.id, node.position)}
          onNodesDelete={(items) => items.forEach((item) => onDeleteNode(item.id))}
          onPaneClick={() => onSelectNode(null)}
          proOptions={{ hideAttribution: true }}
        >
          <Background color="rgba(52, 87, 70, 0.16)" gap={22} size={1} variant={BackgroundVariant.Dots} />
          <CanvasControls />
        </ReactFlow>
      </ReactFlowProvider>
    </section>
  );
}

function CanvasControls() {
  const { fitView, zoomIn, zoomOut } = useReactFlow();
  return (
    <Panel className="canvas-controls" position="bottom-left">
      <button aria-label="放大画布" onClick={() => void zoomIn({ duration: 150 })} title="放大画布" type="button">
        <ZoomIn size={16} />
      </button>
      <button aria-label="缩小画布" onClick={() => void zoomOut({ duration: 150 })} title="缩小画布" type="button">
        <ZoomOut size={16} />
      </button>
      <button aria-label="适应画布" onClick={() => void fitView({ duration: 180, padding: 0.25 })} title="适应画布" type="button">
        <Maximize2 size={16} />
      </button>
    </Panel>
  );
}
