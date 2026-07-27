import { Component, type ErrorInfo, type ReactNode } from "react";
import { colors } from "../../tokens";

type Props = {
  children: ReactNode;
  // Changing this value clears a caught error — pass the current screen id so
  // navigating away from a broken screen recovers instead of staying stuck.
  resetKey?: string;
};

type State = { error: Error | null };

// Catches render-time crashes in a single screen so one bad field can't blank
// the whole app. React has no hook equivalent — an error boundary must be a
// class component implementing getDerivedStateFromError/componentDidCatch.
export class ErrorBoundary extends Component<Props, State> {
  state: State = { error: null };

  static getDerivedStateFromError(error: Error): State {
    return { error };
  }

  componentDidUpdate(prevProps: Props) {
    if (prevProps.resetKey !== this.props.resetKey && this.state.error) {
      this.setState({ error: null });
    }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    // Keep the full stack in the console for debugging; the UI shows a summary.
    console.error("Screen crashed:", error, info.componentStack);
  }

  render() {
    const { error } = this.state;
    if (!error) return this.props.children;

    return (
      <div style={{ padding: 32, maxWidth: 640, margin: "0 auto", textAlign: "center" }}>
        <div style={{ fontSize: 15, fontWeight: 600, marginBottom: 6 }}>This section failed to load</div>
        <div style={{ fontSize: 12, color: colors.textSecondary, marginBottom: 16 }}>
          Something went wrong rendering this screen. The rest of the app still works — pick another
          section from the sidebar, or try again.
        </div>
        <div
          style={{
            background: colors.bgSecondary,
            border: `0.5px solid ${colors.border}`,
            borderRadius: 6,
            padding: 12,
            fontSize: 11,
            fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace",
            color: colors.danger,
            textAlign: "left",
            overflowX: "auto",
            marginBottom: 16,
          }}
        >
          {error.message}
        </div>
        <button
          onClick={() => this.setState({ error: null })}
          style={{
            border: `1px solid ${colors.primary}`,
            background: colors.primary,
            color: "#FFFFFF",
            borderRadius: 6,
            padding: "7px 16px",
            fontSize: 12,
            fontWeight: 500,
            cursor: "pointer",
          }}
        >
          Try again
        </button>
      </div>
    );
  }
}
