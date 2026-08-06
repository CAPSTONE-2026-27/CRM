"""
train.py
========
QLoRA supervised fine-tuning of Llama 3.1 8B Instruct for CRM Deal Intelligence:
(previous deal state + meeting notes) -> updated deal state.

Hardware target : NVIDIA RTX A4000 (16GB VRAM), Windows 11, CUDA 12.6
Base weights    : shared with ../Llama3_CRM — this project trains only an adapter

Run:
    python scripts/generate_dataset.py --rows 800 --validate   # first
    python scripts/train.py
    python scripts/train.py --resume                           # after a crash

Differences from ../Llama3_CRM/scripts/train.py, and why
-------------------------------------------------------
That script trains a different task on the same base model, and most of its
configuration transfers unchanged (4-bit NF4, r=16 LoRA, paged AdamW, gradient
checkpointing) — those were tuned against this exact GPU and are not worth
re-deriving. Three things do differ:

  * max_length is 2048 with room to spare. Measured over the generated set:
    min 991, median 1039, p99 1110, max 1144 tokens — an example carries a full
    17-field state in and a full one out, and that still fits comfortably.
    (3072 was set first on the assumption two states would not fit; measuring
    showed they do. Left at 2048 because the cap only matters if it truncates,
    and silently clipping a target teaches the model to stop mid-object — a
    failure that surfaces as "emits invalid JSON", not "data was clipped".
    Re-measure if the state grows fields or the notes get longer.)
  * 5 epochs, not 8. The lead scorer needed 8 because its decision-critical
    tokens were a small fraction of a long prose reasoning block. Here every
    token of the target is decision-critical: it is all structured JSON, so the
    loss is concentrated on exactly what matters and converges sooner.
  * completion-only loss. See the note on `assistant_only_loss` below.
"""

from __future__ import annotations

import json
import logging
import os
import random
import sys
import time
import traceback
from pathlib import Path

import numpy as np
import torch
import transformers
from datasets import load_dataset
from peft import LoraConfig, get_peft_model, prepare_model_for_kbit_training
from transformers import AutoModelForCausalLM, AutoTokenizer, BitsAndBytesConfig
from trl import SFTConfig, SFTTrainer

sys.path.insert(0, str(Path(__file__).resolve().parent))
from deal_state_format import SYSTEM_PROMPT, build_llama3_prompt  # noqa: E402


# --------------------------------------------------------------------------
# PATHS
# --------------------------------------------------------------------------
PROJECT_ROOT = Path(__file__).resolve().parent.parent

# Base weights are shared with the lead-scoring project rather than duplicated:
# 16GB of safetensors is not worth a second copy, and both adapters must train
# against identical weights or they cannot be served from one loaded model.
MODEL_PATH = Path(
    os.getenv("CRM_BASE_MODEL", PROJECT_ROOT.parent / "Llama3_CRM" / "models" / "Llama-3.1-8B-Instruct")
)
DATA_PATH = PROJECT_ROOT / "data" / "train.jsonl"
OUTPUT_DIR = PROJECT_ROOT / "outputs" / "deal_state_llama3_lora"
LOG_DIR = PROJECT_ROOT / "outputs" / "logs"

SEED = 42
EVAL_FRACTION = 0.10

LOG_DIR.mkdir(parents=True, exist_ok=True)
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s | %(levelname)-8s | %(message)s",
    handlers=[
        logging.StreamHandler(sys.stdout),
        logging.FileHandler(LOG_DIR / "train.log", encoding="utf-8"),
    ],
)
logger = logging.getLogger("deal_state_train")


def set_seed(seed: int = SEED) -> None:
    random.seed(seed)
    np.random.seed(seed)
    torch.manual_seed(seed)
    torch.cuda.manual_seed_all(seed)
    torch.backends.cudnn.deterministic = True
    torch.backends.cudnn.benchmark = False
    os.environ["PYTHONHASHSEED"] = str(seed)


# --------------------------------------------------------------------------
# DATASET
# --------------------------------------------------------------------------
def formatting_func(example: dict) -> str:
    """Build one training string from a generated row.

    Rows come from generate_dataset.py as {instruction, input, output}, where
    `input` is the two-section user turn (previous state + meeting notes) and
    `output` is the target JSON state.
    """
    instruction = (example.get("instruction") or "").strip()
    user_body = (example.get("input") or "").strip()
    output = (example.get("output") or "").strip()

    if not output or not user_body:
        return ""

    user = f"{instruction}\n\n{user_body}" if instruction else user_body
    return build_llama3_prompt(SYSTEM_PROMPT, user, output)


def _is_valid(row: dict) -> bool:
    """Reject rows whose target is not a parseable, complete state.

    Cheap here, expensive later: a malformed target does not raise during
    training, it just teaches the model to emit malformed JSON, and that only
    surfaces as a mysteriously high rejection rate at inference.
    """
    output = (row.get("output") or "").strip()
    if not output or not (row.get("input") or "").strip():
        return False
    try:
        state = json.loads(output)
    except json.JSONDecodeError:
        return False
    return isinstance(state, dict) and len(state) == 17


def load_and_prepare_dataset():
    if not DATA_PATH.exists():
        raise FileNotFoundError(
            f"No training data at {DATA_PATH}. Run:\n"
            "    python scripts/generate_dataset.py --rows 800 --validate"
        )

    dataset = load_dataset("json", data_files=str(DATA_PATH), split="train")
    logger.info("Raw dataset: %d rows", len(dataset))

    before = len(dataset)
    dataset = dataset.filter(_is_valid)
    if len(dataset) != before:
        logger.warning("Dropped %d rows with an unusable target", before - len(dataset))
    logger.info("Clean dataset: %d rows", len(dataset))

    # Shuffled before splitting so the held-out slice is not simply the tail.
    # It matters more here than usual: rows are generated in journey order, so
    # the last N rows would be the late meetings of the final few journeys — a
    # systematically different distribution from the training set.
    split = dataset.train_test_split(test_size=EVAL_FRACTION, seed=SEED, shuffle=True)
    logger.info(
        "Split: %d train / %d eval (%.0f%% held out)",
        len(split["train"]), len(split["test"]), EVAL_FRACTION * 100,
    )
    return split["train"], split["test"]


# --------------------------------------------------------------------------
# MODEL
# --------------------------------------------------------------------------
def load_tokenizer():
    logger.info("Loading tokenizer: %s", MODEL_PATH)
    tokenizer = AutoTokenizer.from_pretrained(
        str(MODEL_PATH), trust_remote_code=True, local_files_only=True
    )
    # Llama 3.1 ships no pad token. Reusing eos keeps padding distinguishable
    # from content; right padding is correct for causal-LM SFT (left padding is
    # only needed for batched generation).
    if tokenizer.pad_token is None:
        tokenizer.pad_token = tokenizer.eos_token
        tokenizer.pad_token_id = tokenizer.eos_token_id
    tokenizer.padding_side = "right"
    return tokenizer


def load_model():
    # 4-bit NF4: an fp16 8B is ~16GB of weights alone and will not fit alongside
    # optimizer state, gradients and activations on a 16GB card. bfloat16 compute
    # matches Ampere's native tensor cores and avoids fp16 overflow.
    bnb_config = BitsAndBytesConfig(
        load_in_4bit=True,
        bnb_4bit_quant_type="nf4",
        bnb_4bit_compute_dtype=torch.bfloat16,
        bnb_4bit_use_double_quant=True,
    )
    compute_dtype = torch.bfloat16 if torch.cuda.is_bf16_supported() else torch.float16
    logger.info("Loading base model (4-bit): %s", MODEL_PATH)
    return AutoModelForCausalLM.from_pretrained(
        str(MODEL_PATH),
        quantization_config=bnb_config,
        device_map="auto",
        torch_dtype=compute_dtype,
        trust_remote_code=True,
        local_files_only=True,
    )


def apply_memory_optimizations(model):
    model.config.use_cache = False  # incompatible with gradient checkpointing
    model = prepare_model_for_kbit_training(
        model,
        use_gradient_checkpointing=True,
        gradient_checkpointing_kwargs={"use_reentrant": False},
    )
    model.gradient_checkpointing_enable(gradient_checkpointing_kwargs={"use_reentrant": False})
    return model


def apply_lora(model):
    # r=16 carried over from the lead-scoring run, where r=32 was measured to be
    # no better and slightly worse. Capacity was not the bottleneck there and is
    # unlikely to be here: this task is a constrained mapping onto a fixed
    # vocabulary, not open generation.
    config = LoraConfig(
        r=16,
        lora_alpha=32,
        lora_dropout=0.05,
        bias="none",
        task_type="CAUSAL_LM",
        target_modules=[
            "q_proj", "k_proj", "v_proj", "o_proj",
            "gate_proj", "up_proj", "down_proj",
        ],
    )
    model = get_peft_model(model, config)
    model.print_trainable_parameters()
    return model


def build_training_config(assistant_only: bool) -> SFTConfig:
    bf16_ok = torch.cuda.is_bf16_supported()
    kwargs = {}
    if assistant_only:
        # Train on the completion only, not the prompt.
        #
        # This matters far more here than for lead scoring. The prompt contains
        # a full 17-field JSON state, and the target is a near-copy of it with a
        # few fields changed. Computing loss over the prompt too would spend
        # most of the gradient teaching the model to reproduce text it was
        # already given, and reward copying the previous state wholesale —
        # exactly the failure mode to avoid, since the whole task is knowing
        # which fields to change.
        kwargs["assistant_only_loss"] = True

    return SFTConfig(
        output_dir=str(OUTPUT_DIR),
        per_device_train_batch_size=1,
        per_device_eval_batch_size=1,
        gradient_accumulation_steps=8,
        learning_rate=1e-4,
        # 5 rather than the lead scorer's 8: every token of this target is
        # decision-critical structured JSON, so loss is concentrated on what
        # matters instead of being diluted by prose reasoning.
        num_train_epochs=5,
        optim="paged_adamw_8bit",
        lr_scheduler_type="cosine",
        warmup_ratio=0.03,
        logging_steps=5,
        eval_strategy="steps",
        eval_steps=25,
        save_steps=25,        # must stay a multiple of eval_steps for load_best_model_at_end
        save_strategy="steps",
        save_total_limit=2,
        load_best_model_at_end=True,
        metric_for_best_model="eval_loss",
        greater_is_better=False,
        bf16=bf16_ok,
        fp16=not bf16_ok,
        # Measured max over the generated set is 1144 tokens (p99 1110), so this
        # never truncates. Verify with scripts/check_lengths.py after changing
        # the state schema or the note templates.
        max_length=2048,
        packing=False,        # separate examples keep the loss signal per-record
        gradient_checkpointing=True,
        gradient_checkpointing_kwargs={"use_reentrant": False},
        report_to="none",
        seed=SEED,
        dataset_num_proc=1,       # Windows: avoid multiprocessing dataloader issues
        dataloader_num_workers=0,
        remove_unused_columns=False,  # formatting_func needs the raw columns
        logging_dir=str(LOG_DIR),
        **kwargs,
    )


def main() -> None:
    logger.info("=" * 70)
    logger.info("CRM Deal Intelligence — QLoRA fine-tuning")
    logger.info("transformers=%s | torch=%s", transformers.__version__, torch.__version__)
    logger.info("=" * 70)

    set_seed(SEED)

    if not torch.cuda.is_available():
        raise RuntimeError("CUDA GPU not detected; 4-bit QLoRA training requires one.")
    logger.info("GPU: %s", torch.cuda.get_device_name(0))

    # Opt-in, unlike the hardcoded True this was copied from. Left on, a run
    # with no checkpoint aborts before the first step, and one with a stale
    # checkpoint silently resumes a different trajectory.
    resume = "--resume" in sys.argv
    if resume and not any(OUTPUT_DIR.glob("checkpoint-*")):
        raise FileNotFoundError(
            f"--resume given but no checkpoint-* in {OUTPUT_DIR}. Drop the flag to start fresh."
        )

    started = time.time()
    try:
        tokenizer = load_tokenizer()
        train_dataset, eval_dataset = load_and_prepare_dataset()

        model = apply_lora(apply_memory_optimizations(load_model()))

        # assistant_only_loss is not in every trl release; fall back rather than
        # fail, but say so loudly — without it the model is being taught to copy
        # its input, and that is worth knowing before reading the eval curve.
        try:
            config = build_training_config(assistant_only=True)
        except TypeError:
            logger.warning(
                "This trl build does not support assistant_only_loss; training on "
                "prompt+completion. Expect the model to over-copy the previous state."
            )
            config = build_training_config(assistant_only=False)

        trainer = SFTTrainer(
            model=model,
            args=config,
            train_dataset=train_dataset,
            eval_dataset=eval_dataset,
            processing_class=tokenizer,
            formatting_func=formatting_func,
        )

        logger.info("Starting training%s...", " (resuming)" if resume else "")
        result = trainer.train(resume_from_checkpoint=resume)
        logger.info("Training complete: %s", result.metrics)

        logger.info("Evaluating best checkpoint on the held-out split...")
        eval_metrics = trainer.evaluate()
        logger.info("Eval: %s", eval_metrics)

        OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
        trainer.model.save_pretrained(str(OUTPUT_DIR))
        tokenizer.save_pretrained(str(OUTPUT_DIR))
        (OUTPUT_DIR / "train_metrics.json").write_text(
            json.dumps(result.metrics, indent=2), encoding="utf-8"
        )
        (OUTPUT_DIR / "eval_metrics.json").write_text(
            json.dumps(eval_metrics, indent=2), encoding="utf-8"
        )

        logger.info("Adapter saved to %s", OUTPUT_DIR)
        logger.info("Total time: %.1f minutes", (time.time() - started) / 60)

    except Exception:
        logger.error("Training failed:\n%s", traceback.format_exc())
        raise
    finally:
        if torch.cuda.is_available():
            torch.cuda.empty_cache()
            torch.cuda.synchronize()


if __name__ == "__main__":
    main()
