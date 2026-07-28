import { useRef, useState } from "react";
import { DndProvider, useDrag, useDrop } from "react-dnd";
import { HTML5Backend } from "react-dnd-html5-backend";
import { colors, flowNodeTypes, FlowNodeType } from "../../tokens";
import { X, Zap, GitFork, Brain, Bot, Play, Bell, ArrowRight, GripVertical, Check, Settings } from "lucide-react";

const PALETTE_TYPE = "WF_PALETTE";
const NODE_TYPE = "WF_NODE";

type BlockTemplate = {
  type: FlowNodeType;
  title: string;
  label: string;
  icon: React.ComponentType<{ size?: number; color?: string }>;
};

type WorkflowNode = BlockTemplate & { id: string; operation?: string };

const BLOCKS: BlockTemplate[] = [
  { type: "trigger", title: "Trigger", label: "New trigger", icon: Zap },
  { type: "condition", title: "Condition", label: "New condition", icon: GitFork },
  { type: "ai", title: "AI action", label: "New AI step", icon: Brain },
  { type: "rpa", title: "RPA bot", label: "New RPA step", icon: Bot },
  { type: "action", title: "Action", label: "New action", icon: Play },
  { type: "log", title: "Notify", label: "New notification", icon: Bell },
];

/* selectable operations per block type */
const OPERATIONS: Record<FlowNodeType, string[]> = {
  trigger: ["Record created", "Record updated", "Field changed", "Scheduled time", "Incoming webhook", "Form submitted"],
  condition: ["Field equals value", "Field greater than", "Contains value", "Is empty", "Date is before/after", "Branch by score"],
  ai: ["Summarize text", "Classify intent", "Score lead", "Draft email reply", "Extract entities", "Sentiment analysis"],
  rpa: ["Login to portal", "Scrape data", "Fill web form", "Download file", "Upload document", "Update ERP record"],
  action: ["Create record", "Update field", "Assign owner", "Send to queue", "Call REST API", "Create task"],
  log: ["Send email", "Send SMS", "Post to Slack", "In-app alert", "Webhook callback", "Write audit log"],
};

let uid = 0;
const nextId = () => `wf-${++uid}`;

/* ---- draggable palette block ---- */
function PaletteBlock({ block }: { block: BlockTemplate }) {
  const [{ isDragging }, drag] = useDrag(
    () => ({
      type: PALETTE_TYPE,
      item: { block },
      collect: (monitor) => ({ isDragging: monitor.isDragging() }),
    }),
    [block]
  );
  const Icon = block.icon;
  return (
    <span
      ref={drag as unknown as React.Ref<HTMLSpanElement>}
      style={{
        display: "inline-flex",
        alignItems: "center",
        gap: 6,
        border: `0.5px solid ${colors.border}`,
        borderRadius: 6,
        padding: "7px 11px",
        fontSize: 11,
        color: colors.textSecondary,
        background: "#FFFFFF",
        cursor: "grab",
        opacity: isDragging ? 0.4 : 1,
      }}
    >
      <Icon size={13} color={colors.textTertiary} />
      {block.title}
    </span>
  );
}

/* ---- reorderable / removable node on the canvas ---- */
function NodeCard({
  node,
  index,
  moveNode,
  onRemove,
  selected,
  onSelect,
}: {
  node: WorkflowNode;
  index: number;
  moveNode: (from: number, to: number) => void;
  onRemove: (id: string) => void;
  selected: boolean;
  onSelect: (id: string) => void;
}) {
  const ref = useRef<HTMLDivElement>(null);
  const t = flowNodeTypes[node.type];

  const [, drop] = useDrop<{ index: number }>({
    accept: NODE_TYPE,
    hover(item, monitor) {
      if (!ref.current) return;
      const dragIndex = item.index;
      const hoverIndex = index;
      if (dragIndex === hoverIndex) return;
      const rect = ref.current.getBoundingClientRect();
      const hoverMiddleX = (rect.right - rect.left) / 2;
      const offset = monitor.getClientOffset();
      if (!offset) return;
      const hoverClientX = offset.x - rect.left;
      if (dragIndex < hoverIndex && hoverClientX < hoverMiddleX) return;
      if (dragIndex > hoverIndex && hoverClientX > hoverMiddleX) return;
      moveNode(dragIndex, hoverIndex);
      item.index = hoverIndex;
    },
  });

  const [{ isDragging }, drag] = useDrag(
    () => ({
      type: NODE_TYPE,
      item: { index },
      collect: (monitor) => ({ isDragging: monitor.isDragging() }),
    }),
    [index]
  );

  drag(drop(ref));

  return (
    <div
      ref={ref}
      onClick={() => onSelect(node.id)}
      style={{
        position: "relative",
        background: t.bg,
        color: t.color,
        borderRadius: 6,
        padding: "10px 14px",
        minWidth: 120,
        textAlign: "center",
        cursor: "pointer",
        opacity: isDragging ? 0.4 : 1,
        outline: selected ? `2px solid ${colors.primary}` : "none",
        outlineOffset: 2,
        boxShadow: isDragging ? "0 4px 12px rgba(0,0,0,0.12)" : "none",
      }}
    >
      <div style={{ display: "flex", alignItems: "center", justifyContent: "center", gap: 4 }}>
        <GripVertical size={11} color={t.color} style={{ opacity: 0.5 }} />
        <span style={{ fontSize: 10, opacity: 0.7, fontWeight: 500 }}>{node.title}</span>
      </div>
      <div style={{ fontSize: 12, fontWeight: 500, marginTop: 2 }}>{node.operation ?? node.label}</div>
      {!node.operation && (
        <div style={{ fontSize: 9, opacity: 0.7, marginTop: 2, display: "flex", alignItems: "center", justifyContent: "center", gap: 3 }}>
          <Settings size={9} color={t.color} /> Click to configure
        </div>
      )}
      <button
        onClick={(e) => { e.stopPropagation(); onRemove(node.id); }}
        style={{
          position: "absolute",
          top: -6,
          right: -6,
          width: 16,
          height: 16,
          borderRadius: "50%",
          border: "none",
          background: "#FFFFFF",
          boxShadow: "0 1px 3px rgba(0,0,0,0.2)",
          display: "flex",
          alignItems: "center",
          justifyContent: "center",
          cursor: "pointer",
          padding: 0,
        }}
        aria-label="Remove step"
      >
        <X size={10} color={colors.textSecondary} />
      </button>
    </div>
  );
}

/* ---- canvas drop zone ---- */
function Canvas({
  nodes,
  onAdd,
  moveNode,
  onRemove,
  selectedId,
  onSelect,
}: {
  nodes: WorkflowNode[];
  onAdd: (block: BlockTemplate) => void;
  moveNode: (from: number, to: number) => void;
  onRemove: (id: string) => void;
  selectedId: string | null;
  onSelect: (id: string) => void;
}) {
  const [{ isOver }, drop] = useDrop(
    () => ({
      accept: PALETTE_TYPE,
      drop: (item: { block: BlockTemplate }) => onAdd(item.block),
      collect: (monitor) => ({ isOver: monitor.isOver() }),
    }),
    [onAdd]
  );

  return (
    <div
      ref={drop as unknown as React.Ref<HTMLDivElement>}
      style={{
        display: "flex",
        alignItems: "center",
        flexWrap: "wrap",
        rowGap: 16,
        background: isOver ? colors.primaryLight : colors.bgSecondary,
        border: `1px dashed ${isOver ? colors.primary : colors.border}`,
        borderRadius: 6,
        padding: 16,
        minHeight: 96,
        transition: "background .12s",
      }}
    >
      {nodes.length === 0 && (
        <div style={{ width: "100%", textAlign: "center", fontSize: 12, color: colors.textTertiary }}>
          Drag building blocks here to compose your workflow
        </div>
      )}
      {nodes.map((n, i) => (
        <div key={n.id} style={{ display: "flex", alignItems: "center" }}>
          <NodeCard node={n} index={i} moveNode={moveNode} onRemove={onRemove} selected={selectedId === n.id} onSelect={onSelect} />
          {i < nodes.length - 1 && (
            <div style={{ width: 20, display: "flex", justifyContent: "center" }}>
              <ArrowRight size={16} color={colors.textTertiary} />
            </div>
          )}
        </div>
      ))}
    </div>
  );
}

/* ---- operation config panel for the selected block ---- */
function ConfigPanel({
  node,
  onAssign,
  onClose,
}: {
  node: WorkflowNode;
  onAssign: (operation: string) => void;
  onClose: () => void;
}) {
  const t = flowNodeTypes[node.type];
  const Icon = node.icon;
  return (
    <div style={{ border: `0.5px solid ${colors.border}`, borderRadius: 6, background: "#FFFFFF", overflow: "hidden" }}>
      <div style={{ display: "flex", alignItems: "center", gap: 8, padding: "10px 12px", borderBottom: `0.5px solid ${colors.border}`, background: colors.bgSecondary }}>
        <div style={{ width: 24, height: 24, borderRadius: 5, background: t.bg, display: "flex", alignItems: "center", justifyContent: "center", flexShrink: 0 }}>
          <Icon size={13} color={t.color} />
        </div>
        <div style={{ flex: 1 }}>
          <div style={{ fontSize: 12, fontWeight: 600, color: colors.textPrimary }}>Configure {node.title}</div>
          <div style={{ fontSize: 11, color: colors.textSecondary }}>Assign an operation for this block</div>
        </div>
        <button
          onClick={onClose}
          style={{ border: "none", background: "transparent", cursor: "pointer", padding: 4, display: "flex" }}
          aria-label="Close configuration"
        >
          <X size={14} color={colors.textSecondary} />
        </button>
      </div>
      <div style={{ padding: 12, display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(200px, 1fr))", gap: 8 }}>
        {OPERATIONS[node.type].map((op) => {
          const active = node.operation === op;
          return (
            <button
              key={op}
              onClick={() => onAssign(op)}
              style={{
                display: "flex",
                alignItems: "center",
                justifyContent: "space-between",
                gap: 8,
                padding: "9px 11px",
                borderRadius: 6,
                border: `1px solid ${active ? colors.primary : colors.border}`,
                background: active ? colors.primaryLight : "#FFFFFF",
                cursor: "pointer",
                fontSize: 12,
                color: colors.textPrimary,
                textAlign: "left",
                fontFamily: "inherit",
              }}
            >
              {op}
              {active && <Check size={13} color={colors.primary} />}
            </button>
          );
        })}
      </div>
    </div>
  );
}

export function WorkflowBuilder({
  initialNodes = [],
}: {
  initialNodes?: { type: FlowNodeType; title: string; label: string }[];
}) {
  const [nodes, setNodes] = useState<WorkflowNode[]>(
    initialNodes.map((n) => {
      const tmpl = BLOCKS.find((b) => b.type === n.type) ?? BLOCKS[0];
      return { ...n, icon: tmpl.icon, id: nextId() };
    })
  );
  const [selectedId, setSelectedId] = useState<string | null>(null);

  const addNode = (block: BlockTemplate) => {
    const id = nextId();
    setNodes((prev) => [...prev, { ...block, id }]);
    setSelectedId(id);
  };

  const moveNode = (from: number, to: number) =>
    setNodes((prev) => {
      const next = [...prev];
      const [moved] = next.splice(from, 1);
      next.splice(to, 0, moved);
      return next;
    });

  const removeNode = (id: string) => {
    setNodes((prev) => prev.filter((n) => n.id !== id));
    setSelectedId((cur) => (cur === id ? null : cur));
  };

  const assignOperation = (id: string, operation: string) =>
    setNodes((prev) => prev.map((n) => (n.id === id ? { ...n, operation } : n)));

  const selectedNode = nodes.find((n) => n.id === selectedId) ?? null;

  return (
    <DndProvider backend={HTML5Backend}>
      <div style={{ display: "flex", flexDirection: "column", gap: 12 }}>
        <Canvas nodes={nodes} onAdd={addNode} moveNode={moveNode} onRemove={removeNode} selectedId={selectedId} onSelect={setSelectedId} />

        {selectedNode && (
          <ConfigPanel node={selectedNode} onAssign={(op) => assignOperation(selectedNode.id, op)} onClose={() => setSelectedId(null)} />
        )}

        <div>
          <div
            style={{
              fontSize: 11,
              fontWeight: 600,
              color: colors.textSecondary,
              textTransform: "uppercase",
              letterSpacing: 0.4,
              marginBottom: 6,
            }}
          >
            Building blocks — drag onto the canvas
          </div>
          <div style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
            {BLOCKS.map((b) => (
              <PaletteBlock key={b.type} block={b} />
            ))}
          </div>
        </div>
      </div>
    </DndProvider>
  );
}
