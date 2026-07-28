\# CRM AI Lead Management Assistant



\## Project Overview



The CRM AI Lead Management Assistant is a Generative AI module developed for a CRM system. It assists sales teams by analyzing manually entered lead details and generating intelligent CRM-specific responses.



The project uses the Meta Llama 3.1 8B Instruct model and is fine-tuned using QLoRA (Quantized Low-Rank Adaptation), allowing efficient training on a single NVIDIA RTX A4000 GPU with 16 GB VRAM.



\---



\## Objectives



\- Fine-tune Llama 3.1 for CRM lead management

\- Generate CRM-specific responses

\- Reduce manual lead analysis effort

\- Build an interactive AI assistant for sales teams



\---



\## Technologies Used



\- Python 3.11

\- PyTorch

\- Transformers

\- PEFT

\- TRL

\- Hugging Face

\- BitsAndBytes

\- CUDA

\- NVIDIA RTX A4000 GPU



\---



\## Model



Base Model:

Meta Llama 3.1 8B Instruct



Fine-Tuning Method:

QLoRA + LoRA



Dataset:

50 CRM instruction-response examples



Training Epochs:

3



Optimizer:

Paged AdamW 8-bit



Scheduler:

Cosine



Quantization:

4-bit NF4



LoRA Rank:

16



LoRA Alpha:

32



Gradient Accumulation:

8



\---



\## Outputs



The model successfully generates CRM-specific responses for manually entered lead details.



Example:



Input:



Company: ABC Technologies

Industry: Healthcare

Budget: 15 Lakhs



Output:



"This customer shows positive engagement because they have expressed interest in the product and are willing to make a decision."



\---



\## Current Limitations



\- Small dataset

\- Generic responses

\- No numerical lead score

\- No follow-up recommendations

\- No CRM database integration



\---



\## Future Improvements



\- 1000+ CRM training samples

\- Lead Score prediction

\- Hot/Warm/Cold lead classification

\- RAG integration using company policies

\- Spring Boot REST API integration

\- React CRM dashboard

\- CRM workflow automation

