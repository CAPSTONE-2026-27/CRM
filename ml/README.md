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
served fine-tune drops in without code changes. Point these at your inference
server, either as environment variables or in
`backend/src/main/resources/application-local.yml`:

```yaml
ai:
  base-url: http://localhost:8000/v1
  model-name: <your-served-model-name>
  api-key: ""          # usually blank for self-hosted vLLM/Ollama
```

The same interface is used by lead scoring and post-meeting analysis, so both
switch over at once.
