"""
inference.py
============

CRM AI Lead Management Assistant
Inference Script for Fine-Tuned Llama 3.1 8B (QLoRA)

Run:
    python scripts/inference.py
"""
import time
import torch
from pathlib import Path

from transformers import (
    AutoTokenizer,
    AutoModelForCausalLM,
)

from peft import PeftModel


# ============================================================
# PATHS
# ============================================================

PROJECT_ROOT = Path(__file__).resolve().parent.parent

MODEL_PATH = PROJECT_ROOT / "models" / "Llama-3.1-8B-Instruct"

ADAPTER_PATH = PROJECT_ROOT / "outputs" / "lead_management_llama3_lora"

SYSTEM_PROMPT = "You are an AI CRM Lead Management Assistant."


# ============================================================
# PROMPT TEMPLATE
# ============================================================

def build_prompt(user_message: str):

    return (
        "<|begin_of_text|>"
        "<|start_header_id|>system<|end_header_id|>\n\n"
        f"{SYSTEM_PROMPT}"
        "<|eot_id|>"
        "<|start_header_id|>user<|end_header_id|>\n\n"
        f"{user_message}"
        "<|eot_id|>"
        "<|start_header_id|>assistant<|end_header_id|>\n\n"
    )


# ============================================================
# DEVICE
# ============================================================

DEVICE = "cuda" if torch.cuda.is_available() else "cpu"

print("=" * 60)
print("CRM AI Lead Management Assistant")
print("=" * 60)
print("Device :", DEVICE)
print("Model  :", MODEL_PATH)
print("Adapter:", ADAPTER_PATH)
print("=" * 60)


# ============================================================
# TOKENIZER
# ============================================================

print("\nLoading tokenizer...")

tokenizer = AutoTokenizer.from_pretrained(
    str(MODEL_PATH),
    trust_remote_code=True,
    local_files_only=True,
)

if tokenizer.pad_token is None:
    tokenizer.pad_token = tokenizer.eos_token

print("Tokenizer loaded.")


# ============================================================
# BASE MODEL
# ============================================================

print("\nLoading base model...")

base_model = AutoModelForCausalLM.from_pretrained(
    str(MODEL_PATH),
    device_map="auto",
    torch_dtype=torch.float16,
    trust_remote_code=True,
    local_files_only=True,
)

print("Base model loaded.")


# ============================================================
# LoRA
# ============================================================

print("\nLoading LoRA adapter...")

model = PeftModel.from_pretrained(
    base_model,
    str(ADAPTER_PATH),
)

print("LoRA adapter loaded.")

print("\nMerging adapter...")

model = model.merge_and_unload()

model.eval()

print("Model ready.")


# ============================================================
# CHAT
# ============================================================

print("\n" + "=" * 60)
print("CRM AI Assistant Ready")
print("Type 'exit' anytime to quit.")
print("=" * 60)


while True:

    print("\nEnter Lead Details (type 'done' when finished):")

    lines = []

    while True:

        line = input()

        if line.lower() == "done":
            break

        if line.lower() == "exit":
            print("\nExiting CRM AI Assistant...")
            raise SystemExit

        lines.append(line)

    user_message = "\n".join(lines)

    prompt = build_prompt(user_message)

    start = time.time()

    print("\nGenerating response...")

    inputs = tokenizer(
        prompt,
        return_tensors="pt"
    )

    inputs = {k: v.to(model.device) for k, v in inputs.items()}

    with torch.no_grad():

        outputs = model.generate(
            **inputs,
            max_new_tokens=200,
            do_sample=True,
            temperature=0.7,
            top_p=0.9,
            repetition_penalty=1.1,
            eos_token_id=tokenizer.eos_token_id,
            pad_token_id=tokenizer.eos_token_id,
        )

    full_response = tokenizer.decode(
        outputs[0],
        skip_special_tokens=False,
        clean_up_tokenization_spaces=False,
    )

    assistant_start = "<|start_header_id|>assistant<|end_header_id|>"

    if assistant_start in full_response:
        response = full_response.split(assistant_start)[-1]
    else:
        response = full_response

    response = response.replace("<|eot_id|>", "").strip()

    print("\n" + "=" * 60)
    print("AI Response")
    print("=" * 60)
    print(response)
    end = time.time()

    print(f"\nInference Time: {end-start:.2f} seconds")