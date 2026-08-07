"""
serve.py
========
Inference API for the Deal Intelligence LLM.

Wholly separate from the Lead Scoring service in ../Llama3_CRM:
its own process, own port, own adapter, own prompt, own request schema. Nothing
here imports from that project and nothing there imports from here. The only
thing shared is the read-only base-model directory on disk, which neither
service writes to.

    port 8000   XgBoost/serve_api.py          deal scoring (existing)
    port 8001   Llama3_CRM/scripts/main.py    lead scoring  (existing, untouched)
    port 8002   this service                  deal intelligence (new)

Contract
--------
POST /v1/deal-state
    {"previous_state": {...17 fields...}, "meeting_notes": "..."}
 -> {"state": {...17 fields...}, "repairs": [...], "changed_fields": [...], ...}

The `state` object is what the XGBoost deal scorer consumes. It is guaranteed
to be scoreable: every reply passes through coerce_state(), which snaps values
onto the trained vocabulary and imputes anything missing. That guarantee is the
point of this service — a model reply is not trusted to be well-formed, because
an unrecognised one-hot value does not raise at scoring time, it silently
scores as zero.

`repairs` is not decoration. A state that needed six repairs and one that needed
none produce equally confident deal scores, and this list is the only thing that
distinguishes them. Log it.

Run:
    python scripts/serve.py
"""

from __future__ import annotations

import json
import logging
import os
import sys
import threading
import time
import traceback
from contextlib import asynccontextmanager, nullcontext
from pathlib import Path
from typing import Any, Optional

import torch
from fastapi import FastAPI
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field
from transformers import AutoModelForCausalLM, AutoTokenizer, BitsAndBytesConfig
from peft import PeftModel

sys.path.insert(0, str(Path(__file__).resolve().parent))
import deal_state_format as fmt  # noqa: E402

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)-5s %(name)s | %(message)s",
)
log = logging.getLogger("deal-intel")


# ============================================================
# CONFIGURATION
# ============================================================

PROJECT_ROOT = Path(__file__).resolve().parent.parent

# Read-only. Shared with the lead-scoring project because 16GB of safetensors is
# not worth duplicating; this service never writes to it.
BASE_MODEL_PATH = Path(os.getenv(
    "DEAL_INTEL_BASE_MODEL",
    PROJECT_ROOT.parent / "Llama3_CRM" / "models" / "Llama-3.1-8B-Instruct",
))

# This service's own adapter. Never the lead-scoring one.
ADAPTER_PATH = Path(os.getenv(
    "DEAL_INTEL_ADAPTER", PROJECT_ROOT / "outputs" / "deal_state_llama3_lora"
))

SERVICE_NAME = "deal-intelligence"
SERVICE_VERSION = fmt.CONTRACT_VERSION

# 8002: 8000 is the XGBoost deal scorer, 8001 the lead-scoring LLM. Two servers
# on one port fails loudly if you are lucky and misroutes silently if not.
PORT = int(os.getenv("DEAL_INTEL_PORT", "8002"))
HOST = os.getenv("DEAL_INTEL_HOST", "0.0.0.0")

# A full 17-field state is ~450 tokens; 768 leaves room for longer objection
# lists without letting a runaway generation stall the GPU.
MAX_NEW_TOKENS = int(os.getenv("DEAL_INTEL_MAX_NEW_TOKENS", "768"))

DEVICE = "cuda" if torch.cuda.is_available() else "cpu"

tokenizer = None
model = None
adapter_loaded = False
_load_error: Optional[str] = None

# model.generate() is not thread-safe under device_map="auto" — Accelerate's
# dispatch hooks move tensors between devices during the forward pass, and two
# overlapping calls corrupt that shared state. FastAPI runs sync routes in a
# thread pool, so every path into generate() is serialised here.
INFERENCE_LOCK = threading.Lock()


def load_model() -> None:
    """Load base weights and this service's adapter, once, at startup."""
    global tokenizer, model, adapter_loaded, _load_error

    log.info("=" * 62)
    log.info("Deal Intelligence LLM  v%s", SERVICE_VERSION)
    log.info("Device : %s", DEVICE)
    log.info("Base   : %s", BASE_MODEL_PATH)
    log.info("Adapter: %s", ADAPTER_PATH)
    log.info("=" * 62)

    try:
        tokenizer = AutoTokenizer.from_pretrained(
            str(BASE_MODEL_PATH), trust_remote_code=True, local_files_only=True
        )
        if tokenizer.pad_token is None:
            tokenizer.pad_token = tokenizer.eos_token

        # The same template train.py installs. Not the tokenizer's stock one:
        # that injects a "Cutting Knowledge Date / Today Date" preamble the
        # adapter was never trained with, and inferring on a different prompt
        # than training used degrades output in ways eval loss never showed.
        # In-memory only — the shared base weights on disk are untouched.
        fmt.apply_chat_template(tokenizer)

        # 4-bit NF4, matching the training config. An fp16 8B is ~16GB of
        # weights alone and would not leave room for activations on a 16GB card;
        # loading fp16 makes Accelerate silently offload layers to CPU and
        # generation becomes invisibly slow.
        bnb_config = BitsAndBytesConfig(
            load_in_4bit=True,
            bnb_4bit_quant_type="nf4",
            bnb_4bit_compute_dtype=torch.bfloat16,
            bnb_4bit_use_double_quant=True,
        )
        base = AutoModelForCausalLM.from_pretrained(
            str(BASE_MODEL_PATH),
            quantization_config=bnb_config,
            device_map="auto",
            trust_remote_code=True,
            local_files_only=True,
        )
        log.info("Base model loaded (4-bit).")

        if (ADAPTER_PATH / "adapter_config.json").exists():
            # Not merged: merging requires dequantising to fp16 first, undoing
            # the memory saving above.
            model = PeftModel.from_pretrained(base, str(ADAPTER_PATH))
            adapter_loaded = True
            log.info("Adapter attached. Service ready.")
        else:
            # Deliberately still serves, so the API, prompt and coercion layer
            # can be exercised before training finishes — but says so on every
            # health check and every response, because base-model output on
            # this task is not fit for scoring.
            model = base
            adapter_loaded = False
            log.warning(
                "NO ADAPTER at %s — serving the BASE model. Output is NOT "
                "production-usable. Run scripts/train.py.", ADAPTER_PATH,
            )
        model.eval()

    except Exception as exc:  # noqa: BLE001
        _load_error = f"{type(exc).__name__}: {exc}"
        log.error("Model load failed: %s", _load_error)
        traceback.print_exc()


# ============================================================
# SCHEMAS
# ============================================================

class DealStateRequest(BaseModel):
    """Previous state plus the meeting that just happened.

    previous_state is optional: the first meeting on an opportunity has no
    prior state, and defaulting is more useful than making the caller
    synthesise one.
    """

    previous_state: Optional[dict] = Field(
        default=None, description="The 17-field state before this meeting; omit for the first."
    )
    meeting_notes: str = Field(..., min_length=1, description="Raw meeting write-up.")


class DealStateResponse(BaseModel):
    state: dict
    changed_fields: list
    repairs: list
    adapter: bool
    model_version: str
    latency_ms: int


# ============================================================
# INFERENCE
# ============================================================

def _generate(prompt: str) -> str:
    with INFERENCE_LOCK:
        inputs = tokenizer(prompt, return_tensors="pt")
        inputs = {k: v.to(model.device) for k, v in inputs.items()}
        prompt_len = inputs["input_ids"].shape[-1]

        # Greedy. The output is a fixed schema over a closed vocabulary, so
        # sampling buys nothing and costs determinism — and this service's
        # contract promises deterministic output.
        with torch.no_grad(), nullcontext():
            outputs = model.generate(
                **inputs,
                max_new_tokens=MAX_NEW_TOKENS,
                do_sample=False,
                repetition_penalty=1.02,
                eos_token_id=tokenizer.eos_token_id,
                pad_token_id=tokenizer.eos_token_id,
            )
        # Sliced by token count rather than split on header markers, which break
        # whenever a reply legitimately contains one.
        return tokenizer.decode(outputs[0][prompt_len:], skip_special_tokens=True).strip()


def _changed(previous: dict, updated: dict) -> list:
    return [
        field for field in fmt.FIELD_ORDER
        if str(previous.get(field)) != str(updated.get(field))
    ]


# ============================================================
# APP
# ============================================================

@asynccontextmanager
async def lifespan(_: FastAPI):
    load_model()
    yield


app = FastAPI(
    title="CRM Deal Intelligence LLM",
    version=SERVICE_VERSION,
    description="Updates structured CRM deal state from meeting notes. "
                "Output is consumed by the XGBoost deal scorer.",
    lifespan=lifespan,
)


@app.get("/health")
def health() -> dict:
    return {
        "service": SERVICE_NAME,
        "version": SERVICE_VERSION,
        "status": "ok" if model is not None else "unavailable",
        "device": DEVICE,
        "adapter_loaded": adapter_loaded,
        "adapter_path": str(ADAPTER_PATH),
        "error": _load_error,
    }


@app.get("/v1/schema")
def schema() -> dict:
    """The exact contract this service emits.

    Published so a consumer can validate against it without importing this
    project — the vocabulary is derived from the XGBoost bundle, not from a
    written spec, and reading it from here is the only way to be sure the two
    agree.
    """
    return {
        "version": SERVICE_VERSION,
        "field_order": fmt.FIELD_ORDER,
        "categorical_values": fmt.CATEGORICAL_VALUES,
        "numeric_ranges": {k: list(v) for k, v in fmt.NUMERIC_RANGES.items()},
        "objection_tokens": fmt.OBJECTION_TOKENS,
        "no_objections_sentinel": fmt.NO_OBJECTIONS,
        "defaults": fmt.DEFAULTS,
    }


@app.post("/v1/deal-state", response_model=DealStateResponse)
def deal_state(request: DealStateRequest):
    if model is None or tokenizer is None:
        # 503 rather than a fabricated state: a caller must be able to tell
        # "the service is down" from "the deal genuinely looks like this".
        return JSONResponse(
            status_code=503,
            content={"error": _load_error or "Model is still loading"},
        )

    started = time.time()

    # An absent previous state means the first meeting on this opportunity.
    # Coerced rather than trusted even when supplied — a caller replaying an
    # older state could hand us a vocabulary this contract no longer accepts.
    previous, previous_repairs = fmt.coerce_state(request.previous_state or {})
    if request.previous_state is None:
        previous["total_meetings"] = 0
        previous_repairs = []

    # The tokenizer's own chat template, exactly as training applied it. Not the
    # hand-built prompt: Llama 3.1's template injects a "Cutting Knowledge Date
    # / Today Date" preamble into the system block, so building the prompt by
    # hand here would infer on a measurably different prompt than the one the
    # adapter was trained on.
    prompt = tokenizer.apply_chat_template(
        fmt.build_messages(previous, request.meeting_notes),
        tokenize=False,
        add_generation_prompt=True,
    )

    try:
        reply = _generate(prompt)
    except Exception as exc:  # noqa: BLE001
        log.error("Generation failed: %s", exc)
        traceback.print_exc()
        return JSONResponse(status_code=500, content={"error": str(exc)})

    raw = fmt.extract_state(reply)
    if raw is None:
        # No JSON at all. Rather than 500, carry the previous state forward with
        # the meeting counted: losing the opportunity's whole state because one
        # generation was malformed is worse than a state that did not move, and
        # the empty changed_fields plus the repair list make it obvious.
        log.warning("Reply held no JSON object: %.200s", reply)
        state = dict(previous)
        state["total_meetings"] = previous["total_meetings"] + 1
        repairs = previous_repairs + ["reply-not-json: previous state carried forward"]
    else:
        state, repairs = fmt.coerce_state(raw)
        repairs = previous_repairs + repairs

    latency_ms = int((time.time() - started) * 1000)
    changed = _changed(previous, state)

    log.info(
        "deal-state: meetings=%s changed=%d repairs=%d %dms%s",
        state["total_meetings"], len(changed), len(repairs), latency_ms,
        "" if adapter_loaded else "  [BASE MODEL - not production output]",
    )
    if repairs:
        log.warning("repairs applied: %s", "; ".join(repairs))

    return DealStateResponse(
        state=state,
        changed_fields=changed,
        repairs=repairs,
        adapter=adapter_loaded,
        model_version=SERVICE_VERSION,
        latency_ms=latency_ms,
    )


if __name__ == "__main__":
    import uvicorn

    # One worker: the model is several GB and each additional worker process
    # would load its own copy.
    uvicorn.run(app, host=HOST, port=PORT)
