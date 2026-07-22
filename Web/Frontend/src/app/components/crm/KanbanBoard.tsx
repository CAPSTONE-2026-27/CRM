import { useState } from "react";
import { DndProvider, useDrag, useDrop } from "react-dnd";
import { HTML5Backend } from "react-dnd-html5-backend";
import { colors, BadgeVariant } from "../../tokens";
import { Badge } from "./ui";

const ITEM_TYPE = "DEAL_CARD";

export type DealCard = {
  id: string;
  name: string;
  value: string;
  probability: string;
  variant: BadgeVariant;
};

export type Column = {
  id: string;
  title: string;
  cards: DealCard[];
};

function parseValue(v: string): number {
  // "$42K" -> 42 ; "$5K" -> 5 ; "Won" -> 0
  const m = v.match(/([\d.]+)/);
  return m ? parseFloat(m[1]) : 0;
}

function formatValue(total: number): string {
  if (total === 0) return "$0";
  return `$${Number.isInteger(total) ? total : total.toFixed(1)}K`;
}

function Card({ card, columnId }: { card: DealCard; columnId: string }) {
  const [{ isDragging }, drag] = useDrag(() => ({
    type: ITEM_TYPE,
    item: { id: card.id, fromColumn: columnId },
    collect: (monitor) => ({ isDragging: monitor.isDragging() }),
  }), [card.id, columnId]);

  return (
    <div
      ref={drag as unknown as React.Ref<HTMLDivElement>}
      style={{
        background: "#FFFFFF",
        border: `0.5px solid ${colors.border}`,
        borderRadius: 6,
        padding: 10,
        cursor: "grab",
        opacity: isDragging ? 0.4 : 1,
        boxShadow: isDragging ? "0 4px 12px rgba(0,0,0,0.12)" : "none",
      }}
    >
      <div style={{ fontSize: 12, fontWeight: 500, color: colors.textPrimary, marginBottom: 6 }}>
        {card.name}
      </div>
      <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between" }}>
        <span style={{ fontSize: 12, color: colors.textSecondary }}>{card.value}</span>
        <Badge label={card.probability} variant={card.variant} />
      </div>
    </div>
  );
}

function Pool({
  column,
  onDropCard,
}: {
  column: Column;
  onDropCard: (cardId: string, fromColumn: string, toColumn: string) => void;
}) {
  const [{ isOver }, drop] = useDrop(() => ({
    accept: ITEM_TYPE,
    drop: (item: { id: string; fromColumn: string }) => {
      if (item.fromColumn !== column.id) {
        onDropCard(item.id, item.fromColumn, column.id);
      }
    },
    collect: (monitor) => ({ isOver: monitor.isOver() }),
  }), [column.id]);

  return (
    <div
      ref={drop as unknown as React.Ref<HTMLDivElement>}
      style={{
        background: isOver ? colors.primaryLight : colors.bgSecondary,
        borderRadius: 8,
        padding: 12,
        border: `1px dashed ${isOver ? colors.primary : colors.border}`,
        transition: "background .12s",
      }}
    >
      <div style={{ marginBottom: 8 }}>
        <div style={{ fontSize: 12, fontWeight: 600, color: colors.textPrimary }}>{column.title}</div>
        <div style={{ fontSize: 11, color: colors.textSecondary }}>
          Drag a company into a pipeline stage to add it
        </div>
      </div>
      <div style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
        {column.cards.map((c) => (
          <div key={c.id} style={{ width: 200 }}>
            <Card card={c} columnId={column.id} />
          </div>
        ))}
        {column.cards.length === 0 && (
          <div style={{ fontSize: 11, color: colors.textTertiary, padding: "8px 0" }}>
            All companies have been added to the pipeline.
          </div>
        )}
      </div>
    </div>
  );
}

function ColumnView({
  column,
  onDropCard,
}: {
  column: Column;
  onDropCard: (cardId: string, fromColumn: string, toColumn: string) => void;
}) {
  const [{ isOver }, drop] = useDrop(() => ({
    accept: ITEM_TYPE,
    drop: (item: { id: string; fromColumn: string }) => {
      if (item.fromColumn !== column.id) {
        onDropCard(item.id, item.fromColumn, column.id);
      }
    },
    collect: (monitor) => ({ isOver: monitor.isOver() }),
  }), [column.id]);

  const total = column.cards.reduce((sum, c) => sum + parseValue(c.value), 0);

  return (
    <div
      ref={drop as unknown as React.Ref<HTMLDivElement>}
      style={{
        background: isOver ? colors.primaryLight : colors.bgSecondary,
        borderRadius: 8,
        padding: 10,
        border: isOver ? `1px dashed ${colors.primary}` : "1px solid transparent",
        transition: "background .12s",
        minHeight: 120,
      }}
    >
      <div style={{ marginBottom: 8 }}>
        <div style={{ fontSize: 12, fontWeight: 600, color: colors.textPrimary }}>{column.title}</div>
        <div style={{ fontSize: 11, color: colors.textSecondary }}>
          {column.cards.length} deals · {formatValue(total)}
        </div>
      </div>
      <div style={{ display: "flex", flexDirection: "column", gap: 8 }}>
        {column.cards.map((c) => (
          <Card key={c.id} card={c} columnId={column.id} />
        ))}
        {column.cards.length === 0 && (
          <div
            style={{
              fontSize: 11,
              color: colors.textTertiary,
              textAlign: "center",
              padding: "16px 0",
              border: `0.5px dashed ${colors.border}`,
              borderRadius: 6,
            }}
          >
            Drop deals here
          </div>
        )}
      </div>
    </div>
  );
}

export function KanbanBoard({
  initialColumns,
  initialPool,
  onCardMoved,
}: {
  initialColumns: Column[];
  initialPool?: Column;
  onCardMoved?: (cardId: string, toColumn: string) => void;
}) {
  // pool (if any) is held in the same state array so cards can move both ways
  const [columns, setColumns] = useState<Column[]>(
    initialPool ? [initialPool, ...initialColumns] : initialColumns
  );

  const poolId = initialPool?.id;
  const pool = poolId ? columns.find((c) => c.id === poolId) : undefined;
  const stageColumns = columns.filter((c) => c.id !== poolId);

  const handleDrop = (cardId: string, fromColumn: string, toColumn: string) => {
    if (fromColumn === toColumn) return;
    setColumns((prev) => {
      const from = prev.find((c) => c.id === fromColumn);
      const card = from?.cards.find((c) => c.id === cardId);
      if (!card) return prev;
      return prev.map((col) => {
        if (col.id === fromColumn) {
          return { ...col, cards: col.cards.filter((c) => c.id !== cardId) };
        }
        if (col.id === toColumn) {
          return { ...col, cards: [...col.cards, card] };
        }
        return col;
      });
    });
    if (toColumn !== poolId) onCardMoved?.(cardId, toColumn);
  };

  return (
    <DndProvider backend={HTML5Backend}>
      <div style={{ display: "flex", flexDirection: "column", gap: 12 }}>
        {pool && <Pool column={pool} onDropCard={handleDrop} />}
        <div
          style={{
            display: "grid",
            gridTemplateColumns: `repeat(${stageColumns.length}, minmax(0,1fr))`,
            gap: 12,
            alignItems: "start",
          }}
        >
          {stageColumns.map((col) => (
            <ColumnView key={col.id} column={col} onDropCard={handleDrop} />
          ))}
        </div>
      </div>
    </DndProvider>
  );
}
