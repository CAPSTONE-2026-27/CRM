import torch

from transformers import (
    AutoTokenizer,
    AutoModelForCausalLM,
)

from peft import PeftModel

# ============================================================
# Configuration
# ============================================================

BASE_MODEL = "meta-llama/Llama-3.1-8B-Instruct"

ADAPTER_PATH = "../outputs/lead_management_llama3_lora"

DEVICE = "cuda" if torch.cuda.is_available() else "cpu"

print("=" * 60)
print("CRM AI Lead Management Assistant")
print("=" * 60)
print(f"Device : {DEVICE}")
print(f"Base Model : {BASE_MODEL}")
print(f"Adapter Path : {ADAPTER_PATH}")
print("=" * 60)