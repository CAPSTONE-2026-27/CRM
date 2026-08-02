"""
serve.py
========

CRM AI Lead Management Assistant
FastAPI inference server for the fine-tuned Llama 3.1 8B (QLoRA) lead scorer.

Loads the model once at startup using the same prompt, 4-bit config, and
post-processing as inference.py / eval_format_consistency.py, then exposes
POST /score for Spring Boot (or anything else) to call over HTTP.

Run:
    python scripts/serve.py
"""
import re
import sys
import threading
import time
import traceback
from pathlib import Path
from typing import List, Optional

import torch
from fastapi import FastAPI
from pydantic import BaseModel
from transformers import AutoModelForCausalLM, AutoTokenizer, BitsAndBytesConfig
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
# REQUEST / RESPONSE SCHEMAS
# ============================================================
# Field names match data/train.jsonl exactly (see ground_reasoning.py's
# FIELD_BUILDERS) -- the previous version of this schema (fullName, company,
# employeeCount, estimatedDealValue, ...) didn't match any field the model
# was actually trained to read, so every request built a prompt the model
# had never seen the shape of.

class LeadRequest(BaseModel):
    companyName: Optional[str] = None
    companyLocation: Optional[str] = None
    employeesCount: Optional[int] = None
    productQuantity: Optional[int] = None
    # Kept as a formatted string (e.g. "Rs.1,54,09,421") rather than a raw
    # number -- every training example uses this formatted-currency form, not
    # a bare float.
    dealValue: Optional[str] = None
    purchaseTimeline: Optional[str] = None
    customerRequirement: Optional[str] = None


class ScoreResponse(BaseModel):
    score: int
    qualification: str
    priority: str
    reason: List[str]
    recommendedAction: str


# ============================================================
# PROMPT BUILDING (delegates to prompt_format.py, same as inference.py)
# ============================================================

def build_lead_message(lead: LeadRequest) -> str:

    fields = [
        ("Company Name", lead.companyName),
        ("Company Location", lead.companyLocation),
        ("Employees Count", lead.employeesCount),
        ("Product Quantity", lead.productQuantity),
        ("Deal Value", lead.dealValue),
        ("Purchase Timeline", lead.purchaseTimeline),
        ("Customer Requirement", lead.customerRequirement),
    ]

    return "\n".join(f"{name}: {value}" for name, value in fields if value is not None)


def build_prompt(lead_message: str) -> str:
    return build_llama3_prompt(SYSTEM_PROMPT, build_user_turn(lead_message))


# ============================================================
# RESPONSE PARSING
# ============================================================
# Parses the actual Lead Score / Qualification / Priority / Reason / bullets
# / Recommended Action format the model is trained to produce -- the
# previous version parsed for a JSON object the model never emitted, so
# every real response fell through to the UNSCORED fallback.

SCORE_RE = re.compile(r"Lead Score:\s*(\d+)/100")
QUALIFICATION_RE = re.compile(r"Qualification:\s*(.+)")
PRIORITY_RE = re.compile(r"Priority:\s*(.+)")
BULLET_RE = re.compile(r"^• (.+)$", re.MULTILINE)
ACTION_RE = re.compile(r"Recommended Action:\s*(.+)", re.DOTALL)


def parse_score(raw_text: str) -> ScoreResponse:

    score_match = SCORE_RE.search(raw_text)
    qual_match = QUALIFICATION_RE.search(raw_text)
    prio_match = PRIORITY_RE.search(raw_text)
    action_match = ACTION_RE.search(raw_text)

    if score_match and qual_match and prio_match:
        return ScoreResponse(
            score=int(score_match.group(1)),
            qualification=qual_match.group(1).strip(),
            priority=prio_match.group(1).strip(),
            reason=[b.strip() for b in BULLET_RE.findall(raw_text)],
            recommendedAction=action_match.group(1).strip() if action_match else "",
        )

    return ScoreResponse(
        score=0,
        qualification="UNSCORED",
        priority="UNSCORED",
        reason=[raw_text.strip()[:500]],
        recommendedAction="",
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

    print("\nLoading base model (4-bit, matching train.py's QLoRA config)...")

    # Loaded in 4-bit rather than full fp16: an fp16 8B model needs ~16GB for
    # weights alone, which doesn't reliably fit on a 16GB RTX A4000 alongside
    # activations/OS overhead. Loading in fp16 here previously caused
    # accelerate to silently offload layers to CPU, making generation
    # extremely and invisibly slow -- the same bug already fixed in
    # inference.py.
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

    print("\nLoading LoRA adapter (not merged, to stay in 4-bit)...")

    # Not merged: merging requires dequantizing back to fp16 first, which
    # would reintroduce the same memory problem.
    peft_model = PeftModel.from_pretrained(
        base_model,
        str(ADAPTER_PATH),
    )

    peft_model.eval()

    model = peft_model

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

    lead_message = build_lead_message(lead)
    prompt = build_prompt(lead_message)

    start = time.time()

    try:
        with inference_lock:

            inputs = tokenizer(prompt, return_tensors="pt")
            inputs = {k: v.to(model.device) for k, v in inputs.items()}

            with torch.no_grad():

                outputs = model.generate(
                    **inputs,
                    max_new_tokens=280,
                    do_sample=False,
                    repetition_penalty=1.05,
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
        return ScoreResponse(
            score=0,
            qualification="UNSCORED",
            priority="UNSCORED",
            reason=["Scoring temporarily unavailable, please retry."],
            recommendedAction="",
        )

    assistant_start = "<|start_header_id|>assistant<|end_header_id|>"

    if assistant_start in full_response:
        raw = full_response.split(assistant_start)[-1]
    else:
        raw = full_response

    raw = raw.replace("<|eot_id|>", "").strip()

    if "<|start_header_id|>" in raw:
        raw = raw.split("<|start_header_id|>")[0].strip()

    raw = reconcile_output(raw, lead_message)

    print(f"\n[/score] Inference time: {time.time() - start:.2f}s")
    print(f"[/score] Raw output: {raw}")

    return parse_score(raw)


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host="0.0.0.0", port=8000)
