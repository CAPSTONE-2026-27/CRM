"""
inference.py
============

CRM AI Lead Management Assistant
Inference Script for Fine-Tuned Llama 3.1 8B (QLoRA)

Run:
    python scripts/inference.py
"""
import sys
import time
import torch
from pathlib import Path

from transformers import (
    AutoTokenizer,
    AutoModelForCausalLM,
    BitsAndBytesConfig,
)

from peft import PeftModel

sys.path.insert(0, str(Path(__file__).resolve().parent))
from prompt_format import SYSTEM_PROMPT, build_llama3_prompt, build_user_turn, reconcile_output  # noqa: E402


# ============================================================
# PATHS
# ============================================================

PROJECT_ROOT = Path(__file__).resolve().parent.parent

MODEL_PATH = PROJECT_ROOT / "models" / "Llama-3.1-8B-Instruct"

ADAPTER_PATH = PROJECT_ROOT / "outputs" / "lead_management_llama3_lora"


# ============================================================
# PROMPT TEMPLATE
# ============================================================
# build_prompt now delegates to prompt_format.py so inference uses the exact
# same system prompt and user-turn shape (instruction + lead fields) that
# train.py trained on. Previously this function hardcoded its own system
# prompt — a different one than training used, and one that explicitly told
# the model not to produce "Recommended Action" — which is why output was
# inconsistent no matter how clean the dataset was.

def build_prompt(user_message: str):
    return build_llama3_prompt(SYSTEM_PROMPT, build_user_turn(user_message))

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

# Loaded in 4-bit (matching train.py's QLoRA config) rather than full fp16:
# an fp16 8B model needs ~16GB for weights alone, which doesn't reliably fit
# on a 16GB RTX A4000 alongside activations/OS overhead. Previously this
# loaded in plain fp16, and accelerate silently offloaded some layers to
# CPU to avoid OOM — generation still "worked" but became drastically
# slower with no visible error, which is why it looked like inference had
# hung. 4-bit keeps everything on-GPU.
bnb_config = BitsAndBytesConfig(
    load_in_4bit=True,
    bnb_4bit_quant_type="nf4",
    bnb_4bit_compute_dtype=torch.bfloat16,
    bnb_4bit_use_double_quant=True,
)

base_model = AutoModelForCausalLM.from_pretrained(
    str(MODEL_PATH),
    quantization_config=bnb_config,
    device_map="auto",
    trust_remote_code=True,
    local_files_only=True,
)

print("Base model loaded.")


# ============================================================
# LoRA
# ============================================================

print("\nLoading LoRA adapter...")

# Not merged: merging requires dequantizing back to fp16 first, which would
# reintroduce the same ~16GB memory problem. Running the adapter directly on
# top of the 4-bit base works fine for generation and has no format impact.
model = PeftModel.from_pretrained(
    base_model,
    str(ADAPTER_PATH),
)

model.eval()

print("LoRA adapter loaded. Model ready.")


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
        max_new_tokens=280,
        do_sample=False,
        ##temperature=0.0,
        repetition_penalty=1.05,
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
    # Remove any accidental continuation

    if "<|start_header_id|>" in response:
        response = response.split("<|start_header_id|>")[0]

    response = response.strip()
    response = reconcile_output(response, user_message)

    print("\n" + "=" * 60)
    print("AI Response")
    print("=" * 60)
    print("\nPrediction")
    print("-"*60)
    print(response)
    print("-"*60)
    end = time.time()

    print(f"\nInference Time: {end-start:.2f} seconds")