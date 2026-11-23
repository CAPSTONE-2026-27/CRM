import { DndProvider, useDrag, useDrop } from "react-dnd";
import { HTML5Backend } from "react-dnd-html5-backend";
import { colors, BadgeVariant } from "../../tokens";
import { Badge } from "./ui";

const ITEM_TYPE = "PIPELINE_CARD";

export type BoardCard = {
  /** Unique across the whole board: lead and deal ids are separate sequences. */
  key: string;
  /** The lead's or deal's own id. */
  id: string;
  /** A lead card is an open lead in the lead columns; a deal card is an opportunity. */
  kind: "lead" | "deal";
  name: string;
  /** Display text for the value, e.g. "₹2,34,567". */
  value: string;
  /** The same figure as a number, for the column total. Carried separately
   *  rather than parsed back out of the display text, which loses precision
   *  on anything abbreviated and depends on the locale's grouping. */
  amount: number;
  probability: string;
  variant: BadgeVariant;
};

export type Column = {
  id: string;
  title: string;
  cards: BoardCard[];
};

type DragItem = { card: BoardCard; fromColumn: string };

function formatValue(total: number): string {
  if (total === 0) return "₹0";
  // Same en-IN lakh/crore grouping as the cards, so a total reads like its parts.
  return `₹${total.toLocaleString("en-IN", { maximumFractionDigits: 0 })}`;
}

function plural(n: number, word: string): string {
  return `${n} ${word}${n === 1 ? "" : "s"}`;
}

/**
 * The column's count and value line. A lead's value is the customer's estimate,
 * not pipeline, so lead totals are kept apart and marked "est." — adding them
 * to deal value would show money the pipeline does not hold.
 */
function columnSummary(cards: BoardCard[]): string {
  const deals = cards.filter((c) => c.kind === "deal");
  const leads = cards.filter((c) => c.kind === "lead");
  const sum = (cs: BoardCard[]) => formatValue(cs.reduce((s, c) => s + c.amount, 0));
  const parts: string[] = [];
  if (deals.length > 0 || leads.length === 0) parts.push(`${plural(deals.length, "deal")} · ${sum(deals)}`);
  if (leads.length > 0) parts.push(`${plural(leads.length, "lead")} · ${sum(leads)} est.`);
  return parts.join(" · ");
}

function Card({
  card,
  columnId,
  onClick,
}: {
  card: BoardCard;
  columnId: string;
  onClick?: (card: BoardCard) => void;
}) {
  const [{ isDragging }, drag] = useDrag(() => ({
    type: ITEM_TYPE,
    item: { card, fromColumn: columnId } satisfies DragItem,
    collect: (monitor) => ({ isDragging: monitor.isDragging() }),
  }), [card, columnId]);

  return (
    <div
      ref={drag as unknown as React.Ref<HTMLDivElement>}
      onClick={onClick ? () => onClick(card) : undefined}
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
      {card.kind === "lead" && (
        <div style={{ fontSize: 10, fontWeight: 600, color: colors.textSecondary, letterSpacing: 0.4, marginBottom: 4 }}>
          LEAD
        </div>
      )}
      <div style={{ fontSize: 12, fontWeight: 500, color: colors.textPrimary, marginBottom: 6 }}>
        {card.name}
      </div>
      <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", gap: 6 }}>
        <span style={{ fontSize: 12, color: colors.textSecondary }}>
          {card.value}
          {card.kind === "lead" ? " est." : ""}
        </span>
        <Badge label={card.probability} variant={card.variant} />
      </div>
    </div>
  );
}

function ColumnView({
  column,
  canDrop,
  onDropCard,
  onCardClick,
}: {
  column: Column;
  canDrop: (card: BoardCard, fromColumn: string, toColumn: string) => boolean;
  onDropCard: (card: BoardCard, fromColumn: string, toColumn: string) => void;
  onCardClick?: (card: BoardCard) => void;
}) {
  const [{ isOver, allowed }, drop] = useDrop(() => ({
    accept: ITEM_TYPE,
    canDrop: (item: DragItem) => item.fromColumn !== column.id && canDrop(item.card, item.fromColumn, column.id),
    drop: (item: DragItem) => onDropCard(item.card, item.fromColumn, column.id),
    collect: (monitor) => ({ isOver: monitor.isOver(), allowed: monitor.canDrop() }),
  }), [column.id, canDrop, onDropCard]);

  // Hovering a column the card may not enter is shown, not silently ignored,
  // so a refused drop does not look like a broken one.
  const refused = isOver && !allowed;

  return (
    <div
      ref={drop as unknown as React.Ref<HTMLDivElement>}
      style={{
        background: refused ? colors.dangerLight : isOver ? colors.primaryLight : colors.bgSecondary,
        borderRadius: 8,
        padding: 10,
        border: refused
          ? `1px dashed ${colors.danger}`
          : isOver
            ? `1px dashed ${colors.primary}`
            : "1px solid transparent",
        transition: "background .12s",
        minHeight: 120,
      }}
    >
      <div style={{ marginBottom: 8 }}>
        <div style={{ fontSize: 12, fontWeight: 600, color: colors.textPrimary }}>{column.title}</div>
        <div style={{ fontSize: 11, color: colors.textSecondary }}>{columnSummary(column.cards)}</div>
      </div>
      <div style={{ display: "flex", flexDirection: "column", gap: 8 }}>
        {column.cards.map((c) => (
          <Card key={c.key} card={c} columnId={column.id} onClick={onCardClick} />
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
            Drop here
          </div>
        )}
      </div>
    </div>
  );
}

/**
 * Controlled: the board renders the columns it is given and reports drops; it
 * never moves a card itself. The caller decides what a drop means (a stage
 * change, a conversion, or nothing) and updates its data, so the board always
 * shows what the server holds rather than a local copy that drifts from it.
 */
export function KanbanBoard({
  columns,
  canDrop = () => true,
  onCardMoved,
  onCardClick,
}: {
  columns: Column[];
  canDrop?: (card: BoardCard, fromColumn: string, toColumn: string) => boolean;
  onCardMoved: (card: BoardCard, fromColumn: string, toColumn: string) => void;
  onCardClick?: (card: BoardCard) => void;
}) {
  return (
    <DndProvider backend={HTML5Backend}>
      {/* Columns share the width until they would get too narrow to read a
          deal name, then the board scrolls sideways instead of squeezing. */}
      <div style={{ overflowX: "auto", paddingBottom: 4 }}>
        <div
          style={{
            display: "grid",
            gridTemplateColumns: `repeat(${columns.length}, minmax(150px, 1fr))`,
            gap: 12,
            alignItems: "start",
            minWidth: "min-content",
          }}
        >
          {columns.map((col) => (
            <ColumnView
              key={col.id}
              column={col}
              canDrop={canDrop}
              onDropCard={onCardMoved}
              onCardClick={onCardClick}
            />
          ))}
        </div>
      </div>
    </DndProvider>
  );
}
