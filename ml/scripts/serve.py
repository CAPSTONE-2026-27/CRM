"""
serve.py
========

CRM AI Lead Management Assistant
FastAPI inference server for the fine-tuned Llama 3.1 8B (QLoRA) lead scorer.

Loads the model once at startup using the same paths, dtype, and Llama-3.1
chat template as inference.py, then exposes POST /score for Spring Boot
(or anything else) to call over HTTP.

Run:
    python scripts/serve.py
"""
import json
import re
import threading
import time
import traceback
from pathlib import Path
from typing import Optional

import torch
from fastapi import FastAPI
from pydantic import BaseModel
from transformers import AutoModelForCausalLM, AutoTokenizer
from peft import PeftModel


# ============================================================
# PATHS
# ============================================================

PROJECT_ROOT = Path(__file__).resolve().parent.parent

MODEL_PATH = PROJECT_ROOT / "models" / "Llama-3.1-8B-Instruct"

ADAPTER_PATH = PROJECT_ROOT / "outputs" / "lead_management_llama3_lora"

SYSTEM_PROMPT = "You are an AI CRM Lead Management Assistant."


# ============================================================
# REQUEST / RESPONSE SCHEMAS
# ============================================================

class LeadRequest(BaseModel):
    fullName: Optional[str] = None
    company: Optional[str] = None
    industry: Optional[str] = None
    employeeCount: Optional[str] = None
    product: Optional[str] = None
    estimatedDealValue: Optional[float] = None
    sourceChannel: Optional[str] = None
    notes: Optional[str] = None


class ScoreResponse(BaseModel):
    score: int
    label: str
    reason: str


# ============================================================
# PROMPT TEMPLATE (same header format as inference.py)
# ============================================================

def build_prompt(user_message: str) -> str:

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


def build_lead_message(lead: LeadRequest) -> str:

    fields = [
        ("Full Name", lead.fullName),
        ("Company", lead.company),
        ("Industry", lead.industry),
        ("Employee Count", lead.employeeCount),
        ("Product", lead.product),
        ("Estimated Deal Value", lead.estimatedDealValue),
        ("Source Channel", lead.sourceChannel),
        ("Notes", lead.notes),
    ]

    lead_block = "\n".join(f"{name}: {value}" for name, value in fields if value is not None)

    return (
        "Analyze the following sales lead and score it for conversion likelihood.\n\n"
        f"{lead_block}\n\n"
        "Respond with ONLY a JSON object in this exact format:\n"
        '{"score": <integer 0-100>, "label": "HOT" | "WARM" | "COLD", "reason": "<one sentence>"}'
    )


# ============================================================
# RESPONSE PARSING
# ============================================================

JSON_PATTERN = re.compile(r"\{.*\}", re.DOTALL)


def parse_score(raw_text: str) -> ScoreResponse:

    match = JSON_PATTERN.search(raw_text)

    if match:
        try:
            data = json.loads(match.group(0))

            return ScoreResponse(
                score=int(data.get("score", 0)),
                label=str(data.get("label", "UNSCORED")).upper(),
                reason=str(data.get("reason", "")).strip(),
            )
        except (ValueError, TypeError):
            pass

    return ScoreResponse(
        score=0,
        label="UNSCORED",
        reason=raw_text.strip()[:500],
    )


# ============================================================
# MODEL LOADING (identical to inference.py)
# ============================================================

DEVICE = "cuda" if torch.cuda.is_available() else "cpu"

tokenizer = None
model = None

# model.generate() is not thread-safe when the model is loaded with
# device_map="auto" (Accelerate's dispatch hooks shuffle tensors between
# devices during the forward pass) — FastAPI runs sync routes in a thread
# pool, so overlapping /score calls corrupt that shared state. Serialize
# all GPU access through this lock.
inference_lock = threading.Lock()


def load_model():

    global tokenizer, model

    print("=" * 60)
    print("CRM AI Lead Management Assistant — Model Server")
    print("=" * 60)
    print("Device :", DEVICE)
    print("Model  :", MODEL_PATH)
    print("Adapter:", ADAPTER_PATH)
    print("=" * 60)

    print("\nLoading tokenizer...")

    tokenizer = AutoTokenizer.from_pretrained(
        str(MODEL_PATH),
        trust_remote_code=True,
        local_files_only=True,
    )

    if tokenizer.pad_token is None:
        tokenizer.pad_token = tokenizer.eos_token

    print("Tokenizer loaded.")

    print("\nLoading base model...")

    base_model = AutoModelForCausalLM.from_pretrained(
        str(MODEL_PATH),
        device_map="auto",
        torch_dtype=torch.float16,
        trust_remote_code=True,
        local_files_only=True,
    )

    print("Base model loaded.")

    print("\nLoading LoRA adapter...")

    peft_model = PeftModel.from_pretrained(
        base_model,
        str(ADAPTER_PATH),
    )

    print("LoRA adapter loaded.")

    print("\nMerging adapter...")

    merged = peft_model.merge_and_unload()

    merged.eval()

    model = merged

    print("Model ready.")


# ============================================================
# FASTAPI APP
# ============================================================

app = FastAPI(title="CRM AI Lead Scoring Service")


@app.on_event("startup")
def on_startup():
    load_model()


@app.get("/health")
def health():
    return {"status": "ok" if model is not None else "loading"}


@app.post("/score", response_model=ScoreResponse)
def score(lead: LeadRequest):

    user_message = build_lead_message(lead)
    prompt = build_prompt(user_message)

    start = time.time()

    try:
        with inference_lock:

            inputs = tokenizer(prompt, return_tensors="pt")
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
    except Exception:
        print("\n[/score] Inference failed:")
        traceback.print_exc()
        return ScoreResponse(score=0, label="UNSCORED", reason="Scoring temporarily unavailable, please retry.")

    assistant_start = "<|start_header_id|>assistant<|end_header_id|>"

    if assistant_start in full_response:
        raw = full_response.split(assistant_start)[-1]
    else:
        raw = full_response

    raw = raw.replace("<|eot_id|>", "").strip()

    print(f"\n[/score] Inference time: {time.time() - start:.2f}s")
    print(f"[/score] Raw output: {raw}")

    return parse_score(raw)


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host="0.0.0.0", port=8000)
