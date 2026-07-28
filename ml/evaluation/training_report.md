\# Training Report



\## Project



CRM AI Lead Management Assistant



\---



\# Base Model



Model Name:

Meta Llama 3.1 8B Instruct



Model Size:

8 Billion Parameters



Framework:

Hugging Face Transformers



\---



\# Fine-Tuning Method



Technique:

QLoRA (Quantized Low-Rank Adaptation)



Adapter:

LoRA



Purpose:

Efficient fine-tuning using limited GPU memory while keeping the original model frozen.



\---



\# Hardware



GPU:

NVIDIA RTX A4000



VRAM:

16 GB



CUDA Version:

13.2



Operating System:

Windows 11



Python Version:

3.11.9



\---



\# Dataset



Dataset Type:

Instruction-Input-Output



Training Examples:

50



Format:

JSONL



Example Fields:



\- instruction

\- input

\- output



\---



\# Quantization Configuration



Load in 4-bit:

True



Quantization Type:

NF4



Double Quantization:

Enabled



Compute Data Type:

bfloat16



\---



\# LoRA Configuration



Rank (r):

16



Alpha:

32



Dropout:

0.05



Bias:

None



Task Type:

CAUSAL\_LM



Target Modules:



\- q\_proj

\- k\_proj

\- v\_proj

\- o\_proj

\- gate\_proj

\- up\_proj

\- down\_proj



Trainable Parameters:



41,943,040



\---



\# Training Configuration



Epochs:

3



Batch Size:

1



Gradient Accumulation:

8



Learning Rate:

2e-4



Scheduler:

Cosine



Warmup Ratio:

0.03



Optimizer:

Paged AdamW 8-bit



Maximum Sequence Length:

2048



Gradient Checkpointing:

Enabled



Seed:

42



\---



\# Output Directory



outputs/

&#x20;   lead\_management\_llama3\_lora/



Generated Files:



adapter\_config.json



adapter\_model.safetensors



tokenizer.json



tokenizer\_config.json



special\_tokens\_map.json



train\_metrics.json



\---



\# Training Status



Environment Setup:

Completed



Dataset Validation:

Completed



QLoRA Configuration:

Completed



LoRA Configuration:

Completed



Training:

Completed Successfully



Adapter Saved:

Yes



Inference Tested:

Yes



Functional Testing:

Completed



Performance Evaluation:

Completed



\---



\# Performance



Inference Time:



Approximately 22 seconds



GPU Memory Usage:



13.5 GB – 14 GB



GPU Utilization:



Dynamic during inference



\---



\# Observations



The model successfully generates CRM-oriented responses after fine-tuning.



Due to the small training dataset (50 examples), responses remain generic and cannot yet perform detailed lead qualification.



Increasing the dataset to 1000+ CRM examples is expected to significantly improve performance.

