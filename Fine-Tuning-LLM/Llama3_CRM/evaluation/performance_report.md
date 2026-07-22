\# CRM AI Lead Management Assistant



\## Performance Evaluation Report



\### Model Details



Base Model:

Llama 3.1 8B Instruct



Fine-tuning:

QLoRA + LoRA



GPU:

NVIDIA RTX A4000 (16GB)



Framework:

Transformers



\----------------------------------------------------



\## Functional Testing



| Test Case | Expected | Actual | Status |

|-----------|----------|--------|--------|

| High Quality Lead | High Priority | Positive Engagement | Pass |

| Startup Lead | Medium Priority | Positive Engagement | Partial |

| Low Budget | Low Priority | Positive Engagement | Fail |

| No Interest | Reject Lead | Low Engagement | Pass |

| Enterprise Lead | High Priority | Positive Engagement | Partial |

| Missing Information | Ask Details | Positive Engagement | Fail |



\----------------------------------------------------



\## Strengths



\- Loads successfully

\- Generates CRM responses

\- Interactive chatbot works

\- LoRA merged successfully



\----------------------------------------------------



\## Limitations



\- Small dataset (50 samples)

\- Generic responses

\- No lead score generation

\- No follow-up recommendation

\- Doesn't ask for missing information



\----------------------------------------------------



\## Future Improvements



\- 1000+ CRM examples

\- Lead Score (0–100)

\- Hot/Warm/Cold classification

\- Sales recommendation

\- Follow-up scheduling

\- RAG integration

