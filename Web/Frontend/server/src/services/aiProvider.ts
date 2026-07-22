import OpenAI from "openai";
import type { ChatCompletion, ChatCompletionMessageParam, ChatCompletionTool } from "openai/resources/index.js";

export class AiUnavailableError extends Error {
  constructor(message = "The AI model server is unreachable or still cold-starting") {
    super(message);
  }
}

export interface AiProvider {
  chat(messages: ChatCompletionMessageParam[], tools?: ChatCompletionTool[]): Promise<ChatCompletion>;
  chatStream(messages: ChatCompletionMessageParam[]): AsyncIterable<string>;
}

const REQUEST_TIMEOUT_MS = Number(process.env.AI_REQUEST_TIMEOUT_MS ?? 30_000);
const COLD_START_RETRIES = 2;

// Points at our own vLLM/Ollama OpenAI-compatible endpoint — no CRM data
// ever leaves our infrastructure. See server/.env.example for AI_BASE_URL.
class LocalAiProvider implements AiProvider {
  private client: OpenAI;
  private model: string;

  constructor() {
    this.client = new OpenAI({
      baseURL: process.env.AI_BASE_URL ?? "http://localhost:8000/v1",
      // A real self-hosted server ignores this; a hosted testing API (Groq,
      // OpenRouter, etc.) requires a real key here — see AI_API_KEY in .env.
      apiKey: process.env.AI_API_KEY || "local",
      timeout: REQUEST_TIMEOUT_MS,
    });
    this.model = process.env.AI_MODEL_NAME ?? "local-model";
  }

  async chat(messages: ChatCompletionMessageParam[], tools?: ChatCompletionTool[]) {
    return this.withColdStartRetry(() =>
      this.client.chat.completions.create({
        model: this.model,
        messages,
        ...(tools ? { tools, tool_choice: "auto" as const } : {}),
      })
    );
  }

  async *chatStream(messages: ChatCompletionMessageParam[]): AsyncIterable<string> {
    const stream = await this.withColdStartRetry(() =>
      this.client.chat.completions.create({ model: this.model, messages, stream: true })
    );
    for await (const chunk of stream) {
      const delta = chunk.choices[0]?.delta?.content;
      if (delta) yield delta;
    }
  }

  private async withColdStartRetry<T>(fn: () => Promise<T>): Promise<T> {
    let lastError: unknown;
    for (let attempt = 0; attempt <= COLD_START_RETRIES; attempt++) {
      try {
        return await fn();
      } catch (err) {
        lastError = err;
        if (attempt < COLD_START_RETRIES) {
          await new Promise((r) => setTimeout(r, 1000 * 2 ** attempt));
        }
      }
    }
    throw new AiUnavailableError(lastError instanceof Error ? lastError.message : undefined);
  }
}

let provider: AiProvider | null = null;

export function getAiProvider(): AiProvider {
  if (provider) return provider;
  const kind = process.env.AI_PROVIDER ?? "local";
  if (kind !== "local") {
    // Only `local` is implemented — see server/.env.example AI_PROVIDER.
    throw new Error(`Unknown AI_PROVIDER "${kind}"; only "local" is implemented`);
  }
  provider = new LocalAiProvider();
  return provider;
}
