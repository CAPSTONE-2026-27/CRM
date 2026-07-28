# Lead scoring model — Llama 3 fine-tune

Fine-tuning pipeline for the CRM's lead-scoring model: given a lead's profile
(industry, company size, deal value, source channel, and the sales executive's
notes) the model returns a 0–100 score, a Hot/Warm/Cold label, and a one-sentence
justification.

```
data/         training and evaluation datasets
scripts/      data prep, training, and inference scripts
evaluation/   evaluation harness and results
test_setup.py environment check — run this first
```

## Serving the model to the CRM

The API talks to any **OpenAI-compatible** chat-completions endpoint, so a
served fine-tune drops in without code changes. Point these variables in
[`../backend/.env`](../backend/.env.example) at your inference server:

```
AI_BASE_URL="http://localhost:8000/v1"
AI_MODEL_NAME="<your-served-model-name>"
AI_API_KEY=""          # usually blank for self-hosted vLLM/Ollama
```

The same interface is used by lead scoring, post-meeting analysis, case routing,
follow-up drafting, and the in-app assistant — so all of them switch over at once.
