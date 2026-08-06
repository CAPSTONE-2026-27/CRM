"""
main.py
=======

CRM local LLM server — the drop-in replacement for Groq.

The CRM backend already talks to exactly one thing: an OpenAI-compatible
``POST /chat/completions`` endpoint (see ``AiChatClient``). Groq was never
integrated any deeper than a default URL, so the entire migration is served by
speaking that same wire format from this machine. No Java changes.

Two models' worth of behaviour out of one set of weights
-------------------------------------------------------
The fine-tuned LoRA in ``outputs/lead_management_llama3_lora`` was trained on
500 examples of exactly one task — lead scoring — emitting a fixed *plain text*
format ("Lead Score: 85/100 / Qualification: Hot / • five bullets"). It has
never seen meeting analysis, 14-parameter deal extraction, or open-ended chat,
and it does not emit JSON.

The CRM needs all four. So requests are routed:

  * A lead-scoring request  -> LoRA enabled, CRM fields mapped onto the trained
                               input shape, and the model's text output bridged
                               back into the JSON schema ``AiScoringClient``
                               already parses.
  * Everything else         -> LoRA disabled (``PeftModel.disable_adapter``),
                               plain Llama 3.1 8B Instruct, which is what Groq
                               was serving anyway.

Both share one 4-bit copy of the base model in VRAM. The adapter is a few dozen
MB of low-rank deltas toggled per request — there is no second model to load.

Endpoints
---------
  POST /v1/chat/completions   OpenAI-compatible, ``stream: true`` supported.
                              This is the one the CRM uses.
  POST /generate              {"prompt": "..."} -> {"response": "..."}
  POST /stream                {"prompt": "..."} -> raw token stream
  GET  /health                Readiness, including whether weights are loaded.

Run:
    python scripts/main.py
    # or: uvicorn scripts.main:app --host 0.0.0.0 --port 8000
"""

from __future__ import annotations

import json
import logging
import os
import re
import sys
import threading
import time
import traceback
import uuid
from contextlib import asynccontextmanager, nullcontext
from pathlib import Path
from typing import Iterator, List, Optional

import torch
from fastapi import FastAPI
from fastapi.responses import JSONResponse, StreamingResponse
from pydantic import BaseModel, Field
from transformers import (
    AutoModelForCausalLM,
    AutoTokenizer,
    BitsAndBytesConfig,
    TextIteratorStreamer,
)
from peft import PeftModel

sys.path.insert(0, str(Path(__file__).resolve().parent))
from prompt_format import (  # noqa: E402
    QUALIFICATION_BY_SCORE,
    SYSTEM_PROMPT,
    build_llama3_prompt,
    build_user_turn,
    reconcile_output,
)

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)-5s %(name)s | %(message)s",
)
log = logging.getLogger("crm-llm")


# ============================================================
# CONFIGURATION
# ============================================================

PROJECT_ROOT = Path(__file__).resolve().parent.parent

MODEL_PATH = Path(os.getenv("CRM_MODEL_PATH", PROJECT_ROOT / "models" / "Llama-3.1-8B-Instruct"))
ADAPTER_PATH = Path(
    os.getenv("CRM_ADAPTER_PATH", PROJECT_ROOT / "outputs" / "lead_management_llama3_lora")
)

SERVED_MODEL_NAME = os.getenv("CRM_SERVED_MODEL_NAME", "crm-llama-3.1-8b-lora")

# Deal analysis asks for 14 parameters, each with a value, confidence and an
# explanation — roughly 900 tokens of JSON. Capping lower silently truncates the
# reply mid-object, which the Java parser then discards as unusable.
DEFAULT_MAX_NEW_TOKENS = int(os.getenv("CRM_MAX_NEW_TOKENS", "1024"))

# The fine-tune's output format is bounded: score, three labels, five bullets,
# one action. 280 covers it with headroom.
LEAD_SCORING_MAX_NEW_TOKENS = int(os.getenv("CRM_LEAD_MAX_NEW_TOKENS", "280"))

# See _normalise_score(). Set to 0 to return the model's raw arithmetic instead.
NORMALISE_LEAD_SCORE = os.getenv("CRM_NORMALISE_LEAD_SCORE", "1") != "0"

# Chattier for open-ended coaching, greedy for anything that must parse as JSON.
CHAT_TEMPERATURE = float(os.getenv("CRM_CHAT_TEMPERATURE", "0.7"))

DEVICE = "cuda" if torch.cuda.is_available() else "cpu"


# ============================================================
# MODEL STATE
# ============================================================

tokenizer = None
model = None
_load_error: Optional[str] = None

# model.generate() is not thread-safe once the model is loaded with
# device_map="auto" — Accelerate's dispatch hooks move tensors between devices
# during the forward pass, and two overlapping calls corrupt that shared state.
# Toggling the LoRA adapter mutates the same module tree, which compounds it.
# FastAPI runs sync routes in a thread pool, so every path into generate() is
# serialised here.
INFERENCE_LOCK = threading.Lock()


def load_model() -> None:
    """Load base weights and the LoRA adapter exactly once, at startup."""
    global tokenizer, model, _load_error

    log.info("=" * 62)
    log.info("CRM local LLM server")
    log.info("Device : %s", DEVICE)
    log.info("Model  : %s", MODEL_PATH)
    log.info("Adapter: %s", ADAPTER_PATH)
    log.info("=" * 62)

    try:
        tokenizer = AutoTokenizer.from_pretrained(
            str(MODEL_PATH), trust_remote_code=True, local_files_only=True
        )
        if tokenizer.pad_token is None:
            tokenizer.pad_token = tokenizer.eos_token
        log.info("Tokenizer loaded.")

        # 4-bit for the same reason train.py and inference.py use it: an fp16 8B
        # needs ~16GB for weights alone, which does not reliably fit on a 16GB
        # card alongside activations. Loading fp16 here makes Accelerate silently
        # offload layers to CPU and generation becomes invisibly slow.
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
        log.info("Base model loaded (4-bit).")

        # Not merged: merging would require dequantising to fp16 first, undoing
        # the memory saving above. Kept attached and toggled per request instead.
        peft_model = PeftModel.from_pretrained(base_model, str(ADAPTER_PATH))
        peft_model.eval()
        model = peft_model

        log.info("Adapter attached. Server ready.")
    except Exception as exc:  # noqa: BLE001 - startup diagnostics
        _load_error = f"{type(exc).__name__}: {exc}"
        log.error("Model load failed: %s", _load_error)
        traceback.print_exc()


# ============================================================
# REQUEST ROUTING
# ============================================================
# Which of the CRM's four call sites a request came from is decided by the
# system prompt it carries. Those prompts are compile-time constants in the Java
# source, so matching on their opening clause is stable — but only as stable as
# the Java string. If AiScoringClient's SYSTEM_PROMPT is ever reworded, this
# marker must be reworded with it, or lead scoring silently falls back to the
# base model. Guarded by tests/test_bridge.py.

LEAD_SCORING_MARKER = "you are a crm lead-scoring assistant"

# Prompts that demand machine-readable output get greedy decoding: sampling buys
# nothing when the reply must match a fixed schema, and costs determinism.
JSON_MARKERS = ("only strict json", "respond with only strict json", "return them as json")


def is_lead_scoring(system_text: str) -> bool:
    return LEAD_SCORING_MARKER in system_text.lower()


def wants_json(system_text: str) -> bool:
    lowered = system_text.lower()
    return any(marker in lowered for marker in JSON_MARKERS)


# ============================================================
# LEAD SCORING BRIDGE — CRM fields in, CRM JSON out
# ============================================================
# The fine-tune reads seven named fields and writes plain text. The CRM sends a
# different field set and expects JSON. This section is the whole adaptation.

# CRM label (AiScoringClient.describe) -> trained field name (train.jsonl).
#
# Ordered to match the training data's field order — the model saw these lines
# in this sequence in all 500 examples, and reordering them is a needless
# prompt-shape difference.
#
# Company Location is the one trained field with no CRM equivalent. It carries
# no points, so its absence costs nothing; it is left out rather than filled
# from the Industry field, which would put a false statement in the prompt.
CRM_TO_TRAINED_FIELD = {
    "Company": "Company Name",
    "Company size": "Employees Count",
    "Product quantity": "Product Quantity",
    "Estimated deal value": "Deal Value",
    "Purchase timeline": "Purchase Timeline",
    "Notes from sales executive": "Customer Requirement",
}

# The five scoring factors, each worth up to 20 points. All five are now
# recordable in the CRM (see migration V17), so a fully-filled lead reaches
# 100/100 and _normalise_score()'s rescaling becomes an identity — the caveat
# it exists for only applies to leads whose rep left fields blank.
SUPPLIED_FACTORS = (
    "Employees Count",
    "Product Quantity",
    "Deal Value",
    "Purchase Timeline",
    "Customer Requirement",
)

BULLET_RE = re.compile(r"^• (.+)$", re.MULTILINE)
BULLET_POINTS_RE = re.compile(r"contributing (\d+) points")
SCORE_RE = re.compile(r"Lead Score:\s*(\d+)/100")
QUALIFICATION_RE = re.compile(r"Qualification:\s*\n?\s*(\w+)")
ACTION_RE = re.compile(r"Recommended Action:\s*\n?(.+)", re.DOTALL)

MAX_POINTS_PER_FACTOR = 20

# ai_score_reason is length 1000 in the Lead entity; five bullets can exceed it.
MAX_REASON_CHARS = 900


def parse_crm_lead_message(user_text: str) -> dict:
    """Read the ``label: value`` block AiScoringClient.describe() produces."""
    fields = {}
    for line in user_text.splitlines():
        label, sep, value = line.partition(":")
        if not sep:
            continue
        value = value.strip()
        if value:
            fields[label.strip()] = value
    return fields


def _employee_count(raw: str) -> Optional[str]:
    """Read a headcount out of the CRM's free-text employee_count column.

    That column is VARCHAR(50), not a number, so it holds whatever was typed or
    imported: "474", "474 employees", "201-500", "5000+". Naively stripping
    non-digits turns "201-500" into 201500 — a mid-sized company promoted to the
    top tier, worth 10 unearned points, with nothing in the output to show for
    it. Ranges are therefore read as ranges and reduced to their midpoint.
    """
    numbers = re.findall(r"\d+", raw)
    if not numbers:
        return None
    if len(numbers) >= 2:
        # A range: "201-500" -> 350. The midpoint is the honest single value —
        # either endpoint would systematically bias every banded lead one way.
        low, high = int(numbers[0]), int(numbers[1])
        return str((low + high) // 2) if high >= low else numbers[0]
    return numbers[0]


def _format_inr(raw: str) -> str:
    """Render a deal value the way every training example does — ``₹1,54,09,421``.

    The fine-tune only ever saw lakh/crore-grouped currency strings, never bare
    floats, so a raw ``15409421.00`` is off-distribution for the field that
    carries a fifth of the score.
    """
    digits = re.sub(r"[^0-9]", "", raw.split(".")[0])
    if not digits:
        return raw
    digits = digits.lstrip("0") or "0"
    if len(digits) <= 3:
        return f"₹{digits}"
    head, tail = digits[:-3], digits[-3:]
    groups = []
    while len(head) > 2:
        groups.insert(0, head[-2:])
        head = head[:-2]
    if head:
        groups.insert(0, head)
    return "₹" + ",".join(groups) + "," + tail


def build_lead_input(crm_fields: dict) -> tuple[str, List[str]]:
    """Map CRM fields onto the trained input block.

    Returns the block and the list of scoring factors actually present, which
    _normalise_score() needs to know what the achievable maximum was.
    """
    lines: List[str] = []
    present: List[str] = []

    for crm_label, trained_name in CRM_TO_TRAINED_FIELD.items():
        raw = crm_fields.get(crm_label)
        if not raw:
            continue

        if trained_name == "Employees Count":
            # describe() writes "480 employees"; the fine-tune wants the number.
            value = _employee_count(raw)
            if value is None:
                continue
        elif trained_name == "Product Quantity":
            digits = re.sub(r"[^0-9]", "", raw)
            if not digits:
                continue
            value = digits
        elif trained_name == "Deal Value":
            value = _format_inr(raw)
        else:
            # Purchase Timeline arrives already canonical — the DB constraint
            # and LeadRequest.@Pattern both reject anything else, so it must not
            # be reshaped here.
            value = raw

        lines.append(f"{trained_name}: {value}")
        if trained_name in SUPPLIED_FACTORS:
            present.append(trained_name)

    # Company Location is left out rather than filled from the CRM's Industry
    # field. It carries no points, and industry is not location — writing one
    # into the other would put a false statement in front of the model to avoid
    # a mild prompt-shape difference.
    return "\n".join(lines), present


def _supplied_bullets(reconciled: str, present: List[str]) -> List[tuple]:
    """Bullets that describe a factor we actually sent, as (text, points).

    The model reliably pads its five-bullet template even when given three
    inputs, inventing the rest — an observed reply scored a lead on "the company
    location, Tokyo, Japan" when no location was sent at all. Those bullets are
    dropped here rather than filtered twice downstream, so neither the score nor
    the reason text can be built from a fabricated signal.
    """
    supplied = []
    for bullet in BULLET_RE.findall(reconciled):
        bullet = bullet.strip()
        if not any(bullet.startswith(factor) for factor in present):
            continue
        points = BULLET_POINTS_RE.search(bullet)
        if points is None:
            continue
        supplied.append((bullet, int(points.group(1))))
    return supplied


def _normalise_score(supplied: List[tuple]) -> Optional[int]:
    """Rescale the model's point total onto the 0-100 range it was trained for.

    The training data gives each of five factors up to 20 points and defines the
    score as their sum. The CRM can only supply three of those five, so the raw
    sum can never exceed 60 — every lead would be Warm or Cold and the CRM would
    never show a Hot lead again. That is a functional break, not a rounding
    quirk.

    So the total is divided by what was actually achievable rather than by a
    fixed 100. Relative ranking between leads is preserved exactly; only the
    scale is restored. Set CRM_NORMALISE_LEAD_SCORE=0 to disable and return the
    model's raw arithmetic.

    Returns None when the reply is too malformed to count bullets from, leaving
    the caller to fall back to the score the model stated in prose.
    """
    if not supplied:
        return None

    total = sum(points for _, points in supplied)
    if not NORMALISE_LEAD_SCORE:
        return max(0, min(100, total))

    achievable = MAX_POINTS_PER_FACTOR * len(supplied)
    return max(0, min(100, round(100 * total / achievable)))


def _label_for(score: int) -> str:
    """Hot/Warm/Cold from the training data's own boundaries.

    Reuses prompt_format.QUALIFICATION_BY_SCORE rather than restating cutoffs,
    so a change to the dataset's banding cannot drift out of sync with this
    server. The labels are already exactly the three the CRM expects.
    """
    return next(label for cutoff, label, _ in QUALIFICATION_BY_SCORE if score <= cutoff)


def lead_scoring_json(raw_output: str, lead_input: str, present: List[str]) -> str:
    """Convert the fine-tune's text format into AiScoringClient's JSON schema.

    Nothing downstream changes: this emits precisely the object the existing
    Java parser already reads, so every fallback, clamp and normalisation in
    AiScoringClient continues to apply on top.
    """
    reconciled = reconcile_output(raw_output, lead_input)

    supplied = _supplied_bullets(reconciled, present)
    score = _normalise_score(supplied)
    if score is None:
        stated = SCORE_RE.search(reconciled)
        if stated is None:
            # No score anywhere. Returning the raw text lets AiJson.extractObject
            # find no JSON and return null, which is exactly the "model
            # unavailable" path AiScoringClient already handles.
            log.warning("Lead scoring reply had no recoverable score: %.200s", reconciled)
            return reconciled
        score = max(0, min(100, int(stated.group(1))))

    label = _label_for(score)

    # Only the vetted bullets. A reason is shown to a sales rep as the account's
    # justification, so a fabricated one is worse than a terse one.
    reason = (
        " ".join(text for text, _ in supplied)[:MAX_REASON_CHARS]
        if supplied
        else "Scored from the lead profile."
    )

    action_match = ACTION_RE.search(reconciled)
    action = action_match.group(1).strip() if action_match else ""

    # Cold is the training data's own "not worth pursuing" band, so the label
    # carries the qualification decision rather than a second threshold that
    # could contradict it.
    qualified = label != "Cold"

    # Which factors the rep left blank, so the reasoning says what the score was
    # and was not based on. A lead scored on two factors and one scored on five
    # are not equally trustworthy, and only this line can tell them apart.
    omitted = [factor for factor in SUPPLIED_FACTORS if factor not in present]
    caveat = f" Scored without {', '.join(omitted)} — not recorded for this lead." if omitted else ""

    payload = {
        "score": score,
        "label": label,
        "reason": reason,
        "qualificationStatus": "QUALIFIED" if qualified else "UNQUALIFIED",
        # An approximation, and a deliberate one: the fine-tune outputs a score,
        # not a separate confidence. AiScoringClient already falls back to the
        # score for this field when the model omits it, so this agrees with the
        # behaviour the CRM expects rather than inventing a second number.
        "qualificationProbability": score,
        "qualificationReasoning": ((action + caveat).strip() or f"Scored {score}/100 ({label})."),
    }
    return json.dumps(payload, ensure_ascii=False)


# ============================================================
# GENERATION
# ============================================================


def build_prompt_from_messages(messages: List["ChatMessage"]) -> str:
    """Render OpenAI-style messages as a Llama 3.1 Instruct prompt."""
    try:
        return tokenizer.apply_chat_template(
            [{"role": m.role, "content": m.content} for m in messages],
            tokenize=False,
            add_generation_prompt=True,
        )
    except Exception:  # noqa: BLE001 - template absent or unusable
        # prompt_format's builder is the exact string train.py used, so this
        # fallback is not a degraded path.
        system = "\n\n".join(m.content for m in messages if m.role == "system")
        user = "\n\n".join(m.content for m in messages if m.role != "system")
        return build_llama3_prompt(system, user)


def _generation_kwargs(max_new_tokens: int, temperature: float) -> dict:
    kwargs = {
        "max_new_tokens": max_new_tokens,
        "repetition_penalty": 1.05,
        "eos_token_id": tokenizer.eos_token_id,
        "pad_token_id": tokenizer.eos_token_id,
    }
    if temperature and temperature > 0:
        kwargs.update(do_sample=True, temperature=temperature, top_p=0.9)
    else:
        kwargs.update(do_sample=False)
    return kwargs


def _adapter_context(use_adapter: bool):
    """Enable the LoRA for the task it was trained on, bypass it otherwise.

    disable_adapter() mutates the shared module tree, so this is only ever
    entered while INFERENCE_LOCK is held.
    """
    return nullcontext() if use_adapter else model.disable_adapter()


def generate_text(prompt: str, *, use_adapter: bool, max_new_tokens: int, temperature: float) -> str:
    """Blocking generation. Raises on failure so the caller can return HTTP 5xx."""
    start = time.time()
    with INFERENCE_LOCK:
        inputs = tokenizer(prompt, return_tensors="pt")
        inputs = {k: v.to(model.device) for k, v in inputs.items()}
        prompt_len = inputs["input_ids"].shape[-1]

        with _adapter_context(use_adapter), torch.no_grad():
            outputs = model.generate(**inputs, **_generation_kwargs(max_new_tokens, temperature))

        # Slice off the prompt by token count rather than string-splitting on
        # header markers — the latter breaks whenever a reply legitimately
        # contains one.
        completion = tokenizer.decode(outputs[0][prompt_len:], skip_special_tokens=True)

    log.info(
        "generate: adapter=%s tokens=%d in %.2fs",
        "on" if use_adapter else "off",
        len(outputs[0]) - prompt_len,
        time.time() - start,
    )
    return completion.strip()


def stream_text(
    prompt: str, *, use_adapter: bool, max_new_tokens: int, temperature: float
) -> Iterator[str]:
    """Yield the reply chunk by chunk as the model produces it."""
    with INFERENCE_LOCK:
        inputs = tokenizer(prompt, return_tensors="pt")
        inputs = {k: v.to(model.device) for k, v in inputs.items()}

        streamer = TextIteratorStreamer(
            tokenizer, skip_prompt=True, skip_special_tokens=True, timeout=120.0
        )

        def run() -> None:
            try:
                with _adapter_context(use_adapter), torch.no_grad():
                    model.generate(
                        **inputs, streamer=streamer, **_generation_kwargs(max_new_tokens, temperature)
                    )
            except Exception:  # noqa: BLE001 - surfaced by the streamer ending early
                log.error("Streamed generation failed:")
                traceback.print_exc()

        worker = threading.Thread(target=run, daemon=True)
        worker.start()
        try:
            for chunk in streamer:
                if chunk:
                    yield chunk
        finally:
            # Reached on client disconnect too, where the generator is closed
            # mid-iteration. Join so the lock is never released while the worker
            # is still touching the model.
            worker.join(timeout=180)


# ============================================================
# SCHEMAS
# ============================================================


class ChatMessage(BaseModel):
    role: str = "user"
    content: str = ""


class ChatCompletionRequest(BaseModel):
    """The subset of the OpenAI schema AiChatClient sends, plus common extras."""

    model: Optional[str] = None
    messages: List[ChatMessage] = Field(default_factory=list)
    stream: bool = False
    temperature: Optional[float] = None
    max_tokens: Optional[int] = None


class GenerateRequest(BaseModel):
    prompt: str = ""
    max_tokens: Optional[int] = None
    temperature: Optional[float] = None


class GenerateResponse(BaseModel):
    response: str


# ============================================================
# APP
# ============================================================


@asynccontextmanager
async def lifespan(_: FastAPI):
    load_model()
    yield


app = FastAPI(title="CRM Local LLM Server", lifespan=lifespan)


def _plan(request: ChatCompletionRequest) -> tuple[str, bool, int, float, Optional[tuple]]:
    """Decide how to answer one request.

    Returns (prompt, use_adapter, max_new_tokens, temperature, lead_context),
    where lead_context is non-None only for the lead-scoring route and carries
    what the JSON bridge needs afterwards.
    """
    system_text = "\n".join(m.content for m in request.messages if m.role == "system")
    user_text = "\n".join(m.content for m in request.messages if m.role == "user")

    if is_lead_scoring(system_text):
        crm_fields = parse_crm_lead_message(user_text)
        lead_input, present = build_lead_input(crm_fields)
        prompt = build_llama3_prompt(SYSTEM_PROMPT, build_user_turn(lead_input))
        max_new = request.max_tokens or LEAD_SCORING_MAX_NEW_TOKENS
        log.info("route=lead-scoring adapter=on factors=%s", present or "none")
        return prompt, True, max_new, 0.0, (lead_input, present)

    prompt = build_prompt_from_messages(request.messages)
    max_new = request.max_tokens or DEFAULT_MAX_NEW_TOKENS
    if request.temperature is not None:
        temperature = request.temperature
    else:
        temperature = 0.0 if wants_json(system_text) else CHAT_TEMPERATURE
    log.info("route=base adapter=off json=%s temp=%.2f", wants_json(system_text), temperature)
    return prompt, False, max_new, temperature, None


def _not_ready() -> Optional[JSONResponse]:
    if model is None or tokenizer is None:
        # A 503 rather than a fabricated reply: AiChatClient catches the error,
        # logs it and returns null, which is the same signal it used for an
        # unreachable Groq. Every existing CRM fallback then applies unchanged.
        return JSONResponse(
            status_code=503,
            content={"error": {"message": _load_error or "Model is still loading", "type": "unavailable"}},
        )
    return None


@app.get("/health")
def health():
    return {
        "status": "ok" if model is not None else "unavailable",
        "device": DEVICE,
        "model": SERVED_MODEL_NAME,
        "adapter": str(ADAPTER_PATH),
        "error": _load_error,
    }


@app.post("/v1/chat/completions")
@app.post("/chat/completions")
def chat_completions(request: ChatCompletionRequest):
    """OpenAI-compatible. This is the endpoint the CRM backend calls."""
    unavailable = _not_ready()
    if unavailable is not None:
        return unavailable

    try:
        prompt, use_adapter, max_new, temperature, lead_context = _plan(request)
    except Exception as exc:  # noqa: BLE001
        log.error("Request planning failed: %s", exc)
        traceback.print_exc()
        return JSONResponse(status_code=500, content={"error": {"message": str(exc)}})

    completion_id = f"chatcmpl-{uuid.uuid4().hex[:24]}"
    created = int(time.time())

    if request.stream:
        return StreamingResponse(
            _sse_chunks(completion_id, created, prompt, use_adapter, max_new, temperature, lead_context),
            media_type="text/event-stream",
            headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
        )

    try:
        text = generate_text(
            prompt, use_adapter=use_adapter, max_new_tokens=max_new, temperature=temperature
        )
        if lead_context is not None:
            text = lead_scoring_json(text, *lead_context)
    except Exception as exc:  # noqa: BLE001
        log.error("Inference failed: %s", exc)
        traceback.print_exc()
        return JSONResponse(status_code=500, content={"error": {"message": str(exc)}})

    return {
        "id": completion_id,
        "object": "chat.completion",
        "created": created,
        "model": request.model or SERVED_MODEL_NAME,
        "choices": [
            {"index": 0, "message": {"role": "assistant", "content": text}, "finish_reason": "stop"}
        ],
    }


def _sse_chunks(
    completion_id: str,
    created: int,
    prompt: str,
    use_adapter: bool,
    max_new: int,
    temperature: float,
    lead_context,
) -> Iterator[str]:
    """Emit the OpenAI streaming wire format AiChatClient.stream() parses."""

    def envelope(delta: dict, finish: Optional[str] = None) -> str:
        payload = {
            "id": completion_id,
            "object": "chat.completion.chunk",
            "created": created,
            "model": SERVED_MODEL_NAME,
            "choices": [{"index": 0, "delta": delta, "finish_reason": finish}],
        }
        return f"data: {json.dumps(payload, ensure_ascii=False)}\n\n"

    yield envelope({"role": "assistant"})
    try:
        if lead_context is not None:
            # Streaming a JSON bridge would emit the pre-bridge text format, so
            # the built object is sent as one chunk. Nothing streams this route
            # in practice — only the deal coach streams, and it takes the base
            # path below.
            text = generate_text(
                prompt, use_adapter=use_adapter, max_new_tokens=max_new, temperature=temperature
            )
            yield envelope({"content": lead_scoring_json(text, *lead_context)})
        else:
            for chunk in stream_text(
                prompt, use_adapter=use_adapter, max_new_tokens=max_new, temperature=temperature
            ):
                yield envelope({"content": chunk})
        yield envelope({}, finish="stop")
    except Exception as exc:  # noqa: BLE001
        log.error("Streamed inference failed: %s", exc)
        traceback.print_exc()
        # No way to change the status code once the stream is open, so the error
        # is signalled by ending it. AiChatClient sees a short stream, the
        # controller sends its `error` event, and the frontend shows its notice.
        yield envelope({}, finish="stop")
    yield "data: [DONE]\n\n"


@app.post("/generate", response_model=GenerateResponse)
def generate(request: GenerateRequest):
    """Plain prompt-in/text-out, for scripts and manual testing.

    Takes a fully-formed prompt string, so it always runs the base model — there
    are no role-separated messages to detect a lead-scoring call from. Use
    /v1/chat/completions for anything that needs the adapter.
    """
    unavailable = _not_ready()
    if unavailable is not None:
        return unavailable
    try:
        text = generate_text(
            request.prompt,
            use_adapter=False,
            max_new_tokens=request.max_tokens or DEFAULT_MAX_NEW_TOKENS,
            temperature=request.temperature if request.temperature is not None else 0.0,
        )
    except Exception as exc:  # noqa: BLE001
        log.error("Inference failed: %s", exc)
        traceback.print_exc()
        return JSONResponse(status_code=500, content={"error": {"message": str(exc)}})
    return GenerateResponse(response=text)


@app.post("/stream")
def stream(request: GenerateRequest):
    """Raw token stream for the same plain-prompt shape as /generate."""
    unavailable = _not_ready()
    if unavailable is not None:
        return unavailable
    return StreamingResponse(
        stream_text(
            request.prompt,
            use_adapter=False,
            max_new_tokens=request.max_tokens or DEFAULT_MAX_NEW_TOKENS,
            temperature=request.temperature if request.temperature is not None else 0.0,
        ),
        media_type="text/plain",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )


if __name__ == "__main__":
    import uvicorn

    # One worker deliberately: the model is several GB and every additional
    # worker process would load its own copy.
    #
    # Port 8001, not 8000 — XgBoost/serve_api.py already owns 8000 and
    # DealScoringClient defaults to it. Two servers on one port fails loudly at
    # startup if you are lucky and silently misroutes if you are not.
    uvicorn.run(app, host=os.getenv("CRM_HOST", "0.0.0.0"), port=int(os.getenv("CRM_PORT", "8001")))
