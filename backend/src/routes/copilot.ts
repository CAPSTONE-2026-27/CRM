import { Router } from "express";
import { z } from "zod";
import type { ChatCompletionMessageParam } from "openai/resources/index.js";
import { requireAuth, requirePermission } from "../middleware/auth.js";
import { asyncHandler } from "../middleware/errorHandler.js";
import { getAiProvider, AiUnavailableError } from "../services/aiProvider.js";
import { toolsForAuth, executeTool } from "../services/aiTools.js";

export const copilotRouter = Router();
copilotRouter.use(requireAuth);
copilotRouter.use(requirePermission("copilot"));

const chatSchema = z.object({
  messages: z.array(z.object({ role: z.enum(["user", "assistant"]), content: z.string() })).min(1),
});

const SYSTEM_PROMPT =
  "You are the TechCRM AI copilot. Answer questions about leads, the sales pipeline, and support cases using the search tools provided. Be concise.";

const MAX_TOOL_ROUNDS = 3;

copilotRouter.post(
  "/chat",
  asyncHandler(async (req, res) => {
    const { messages: userMessages } = chatSchema.parse(req.body);
    const auth = req.auth!;
    const tools = toolsForAuth(auth);
    const ai = getAiProvider();

    res.setHeader("Content-Type", "text/event-stream");
    res.setHeader("Cache-Control", "no-cache");
    res.setHeader("Connection", "keep-alive");
    res.flushHeaders();

    const send = (event: string, data: unknown) => {
      res.write(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`);
    };

    let messages: ChatCompletionMessageParam[] = [
      { role: "system", content: SYSTEM_PROMPT },
      ...userMessages,
    ];

    try {
      // Resolve any tool calls first (non-streaming), then stream the final answer.
      for (let round = 0; round < MAX_TOOL_ROUNDS; round++) {
        const completion = await ai.chat(messages, tools.length > 0 ? tools : undefined);
        const message = completion.choices[0]?.message;
        if (!message?.tool_calls?.length) break;

        messages = [...messages, message];
        for (const call of message.tool_calls) {
          send("tool_call", { name: call.function.name });
          const result = await executeTool(call.function.name, call.function.arguments, auth);
          messages.push({ role: "tool", tool_call_id: call.id, content: JSON.stringify(result) });
        }
      }

      for await (const delta of ai.chatStream(messages)) {
        send("delta", { text: delta });
      }
      send("done", {});
    } catch (err) {
      if (err instanceof AiUnavailableError) {
        send("error", { message: "The AI copilot is temporarily unavailable. Please try again shortly." });
      } else {
        send("error", { message: "Something went wrong generating a response." });
      }
    } finally {
      res.end();
    }
  })
);
