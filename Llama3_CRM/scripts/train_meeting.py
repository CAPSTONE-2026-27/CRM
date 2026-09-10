"""
train_meeting.py
================
QLoRA fine-tune for lead qualification-meeting extraction:
meeting notes (free text) -> five business signals (JSON).

Trains a SECOND adapter. The capture-time lead scorer in
outputs/lead_management_llama3_lora is not touched, retrained or replaced --
it works, and it stays exactly as it is. Both adapters attach to the same base
weights and are toggled per request by scripts/main.py, so serving two
fine-tuned behaviours costs one model in VRAM.

The model never produces a score. It reads the meeting and names five values;
LeadScoreFluctuationEngine.java turns those into a meeting score, a priority
band and the lead's updated score. Keeping the arithmetic out of the model is
what makes a score recomputable -- and challengeable -- from stored signals
months later, and lets the weights be retuned without retraining anything.

Run:
    python scripts/generate_meeting_dataset.py --rows 500   # first
    python scripts/train_meeting.py
    python scripts/train_meeting.py --resume                # after a crash
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
import meeting_prompt_format as fmt  # noqa: E402

PROJECT_ROOT = Path(__file__).resolve().parent.parent
MODEL_PATH = PROJECT_ROOT / "models" / "Llama-3.1-8B-Instruct"
DATA_PATH = PROJECT_ROOT / "data" / "meeting_train.jsonl"
OUTPUT_DIR = PROJECT_ROOT / "outputs" / "lead_meeting_llama3_lora"
LOG_DIR = PROJECT_ROOT / "outputs" / "logs"

SEED = 42
EVAL_FRACTION = 0.10

LOG_DIR.mkdir(parents=True, exist_ok=True)
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s | %(levelname)-8s | %(message)s",
    handlers=[
        logging.StreamHandler(sys.stdout),
        logging.FileHandler(LOG_DIR / "train_meeting.log", encoding="utf-8"),
    ],
)
logger = logging.getLogger("meeting_extract_train")

_TOKENIZER = None


def set_seed(seed: int = SEED) -> None:
    random.seed(seed)
    np.random.seed(seed)
    torch.manual_seed(seed)
    torch.cuda.manual_seed_all(seed)
    torch.backends.cudnn.deterministic = True
    torch.backends.cudnn.benchmark = False
    os.environ["PYTHONHASHSEED"] = str(seed)


# --------------------------------------------------------------------------
# CHAT TEMPLATE
# --------------------------------------------------------------------------
# Llama 3.1 Instruct format with two departures from the tokenizer's stock
# template, both required:
#
#   1. {% generation %} markers. TRL's assistant_only_loss needs them to mask
#      the prompt, and trl refuses to patch a template that lacks them. The
#      masking matters more here than on most tasks: the prompt is a 200-word
#      meeting note and the target is five short values, so training on the
#      whole sequence would spend nearly all the gradient teaching the model to
#      reproduce notes it was already handed.
#   2. No "Cutting Knowledge Date / Today Date" preamble. The stock template
#      injects one; a date that shifts between training and serving is silent
#      prompt drift that makes a model perform worse than its eval loss says.
#
# scripts/main.py must install this same template when serving this adapter.
CHAT_TEMPLATE = (
    "{{- bos_token }}"
    "{%- for message in messages %}"
    "{%- if message['role'] == 'assistant' %}"
    "{{- '<|start_header_id|>assistant<|end_header_id|>\n\n' }}"
    "{%- generation %}"
    "{{- message['content'] | trim + '<|eot_id|>' }}"
    "{%- endgeneration %}"
    "{%- else %}"
    "{{- '<|start_header_id|>' + message['role'] + '<|end_header_id|>\n\n' "
    "+ message['content'] | trim + '<|eot_id|>' }}"
    "{%- endif %}"
    "{%- endfor %}"
    "{%- if add_generation_prompt %}"
    "{{- '<|start_header_id|>assistant<|end_header_id|>\n\n' }}"
    "{%- endif %}"
)


def apply_chat_template(tokenizer):
    """Install this task's template. Mutates only the in-memory tokenizer --
    the shared base-model directory on disk is never written to, so the
    lead-scoring service that loads the same weights is unaffected."""
    tokenizer.chat_template = CHAT_TEMPLATE
    return tokenizer


# --------------------------------------------------------------------------
# DATASET
# --------------------------------------------------------------------------
def _is_valid(row: dict) -> bool:
    """Reject rows whose target is not a complete, in-vocabulary signal set.

    Cheap here, expensive later: a malformed target does not raise during
    training, it teaches the model to emit malformed output, and that surfaces
    only as a mysteriously high rejection rate at inference.
    """
    messages = row.get("messages")
    if not messages or len(messages) != 3 or messages[-1].get("role") != "assistant":
        return False
    try:
        signals = json.loads(messages[-1]["content"])
    except (json.JSONDecodeError, TypeError):
        return False
    if not isinstance(signals, dict) or len(signals) != len(fmt.FIELD_ORDER):
        return False
    return all(signals.get(f) in fmt.ALLOWED_VALUES[f] for f in fmt.FIELD_ORDER)


def load_and_prepare_dataset():
    if not DATA_PATH.exists():
        raise FileNotFoundError(
            f"No training data at {DATA_PATH}. Run:\n"
            "    python scripts/generate_meeting_dataset.py --rows 500"
        )

    dataset = load_dataset("json", data_files=str(DATA_PATH), split="train")
    logger.info("Raw dataset: %d rows", len(dataset))

    before = len(dataset)
    dataset = dataset.filter(_is_valid)
    if len(dataset) != before:
        logger.warning("Dropped %d rows with an unusable target", before - len(dataset))
    logger.info("Clean dataset: %d rows", len(dataset))

    # Shuffled before splitting: rows are generated in stratified band order, so
    # the tail would be a systematically different distribution from the rest.
    split = dataset.train_test_split(test_size=EVAL_FRACTION, seed=SEED, shuffle=True)
    logger.info("Split: %d train / %d eval", len(split["train"]), len(split["test"]))
    return split["train"], split["test"]


# --------------------------------------------------------------------------
# MODEL
# --------------------------------------------------------------------------
def load_tokenizer():
    tokenizer = AutoTokenizer.from_pretrained(
        str(MODEL_PATH), trust_remote_code=True, local_files_only=True)
    if tokenizer.pad_token is None:
        tokenizer.pad_token = tokenizer.eos_token
        tokenizer.pad_token_id = tokenizer.eos_token_id
    tokenizer.padding_side = "right"
    apply_chat_template(tokenizer)
    global _TOKENIZER
    _TOKENIZER = tokenizer
    return tokenizer


def load_model():
    # 4-bit NF4: an fp16 8B is ~16GB of weights alone and leaves no room for
    # optimizer state and activations on a 16GB card.
    bnb_config = BitsAndBytesConfig(
        load_in_4bit=True,
        bnb_4bit_quant_type="nf4",
        bnb_4bit_compute_dtype=torch.bfloat16,
        bnb_4bit_use_double_quant=True,
    )
    logger.info("Loading base model (4-bit): %s", MODEL_PATH)
    return AutoModelForCausalLM.from_pretrained(
        str(MODEL_PATH),
        quantization_config=bnb_config,
        device_map="auto",
        torch_dtype=torch.bfloat16 if torch.cuda.is_bf16_supported() else torch.float16,
        trust_remote_code=True,
        local_files_only=True,
    )


def apply_memory_optimizations(model):
    model.config.use_cache = False  # incompatible with gradient checkpointing
    model = prepare_model_for_kbit_training(
        model, use_gradient_checkpointing=True,
        gradient_checkpointing_kwargs={"use_reentrant": False})
    model.gradient_checkpointing_enable(gradient_checkpointing_kwargs={"use_reentrant": False})
    return model


def apply_lora(model):
    # r=16 carried over from both previous fine-tunes on this GPU, where r=32
    # measured no better. Unlikely to bind here either: the output is five
    # values from closed vocabularies, not open generation.
    config = LoraConfig(
        r=16, lora_alpha=32, lora_dropout=0.05, bias="none", task_type="CAUSAL_LM",
        target_modules=["q_proj", "k_proj", "v_proj", "o_proj",
                        "gate_proj", "up_proj", "down_proj"])
    model = get_peft_model(model, config)
    model.print_trainable_parameters()
    return model


def build_training_config(assistant_only: bool) -> SFTConfig:
    bf16_ok = torch.cuda.is_bf16_supported()
    kwargs = {"assistant_only_loss": True} if assistant_only else {}
    return SFTConfig(
        output_dir=str(OUTPUT_DIR),
        per_device_train_batch_size=1,
        per_device_eval_batch_size=1,
        gradient_accumulation_steps=8,
        learning_rate=1e-4,
        # 4 epochs. The target is ~40 tokens of JSON over a closed vocabulary --
        # far less to learn than the deal-state task, which needed a full state
        # reproduced. The eval curve decides; load_best_model_at_end keeps the
        # best checkpoint regardless of where it lands.
        num_train_epochs=4,
        optim="paged_adamw_8bit",
        lr_scheduler_type="cosine",
        warmup_ratio=0.03,
        logging_steps=5,
        eval_strategy="steps",
        eval_steps=20,
        save_steps=20,      # must stay a multiple of eval_steps for load_best_model_at_end
        save_strategy="steps",
        save_total_limit=2,
        load_best_model_at_end=True,
        metric_for_best_model="eval_loss",
        greater_is_better=False,
        bf16=bf16_ok,
        fp16=not bf16_ok,
        # A 250-word note plus the system prompt is comfortably under 1024;
        # 1536 leaves headroom without padding cost, since packing is off and
        # batch size is 1. Verify with scripts/check_meeting_lengths.py.
        max_length=1536,
        packing=False,
        gradient_checkpointing=True,
        gradient_checkpointing_kwargs={"use_reentrant": False},
        report_to="none",
        seed=SEED,
        dataset_num_proc=1,        # Windows: avoid multiprocessing dataloader issues
        dataloader_num_workers=0,
        # The dataset carries meeting_id, lead_score, signals and meta alongside
        # `messages`; the trainer must strip them rather than collate them.
        remove_unused_columns=True,
        logging_dir=str(LOG_DIR),
        **kwargs,
    )


def main() -> None:
    logger.info("=" * 70)
    logger.info("Lead Meeting Extraction — QLoRA fine-tuning")
    logger.info("transformers=%s | torch=%s", transformers.__version__, torch.__version__)
    logger.info("Output: %s", OUTPUT_DIR)
    logger.info("=" * 70)

    set_seed(SEED)
    if not torch.cuda.is_available():
        raise RuntimeError("CUDA GPU not detected; 4-bit QLoRA training requires one.")
    logger.info("GPU: %s", torch.cuda.get_device_name(0))

    # Opt-in. Left always-on, a run with no checkpoint aborts before the first
    # step and one with a stale checkpoint silently resumes another trajectory.
    resume = "--resume" in sys.argv
    if resume and not any(OUTPUT_DIR.glob("checkpoint-*")):
        raise FileNotFoundError(
            f"--resume given but no checkpoint-* in {OUTPUT_DIR}. Drop the flag.")

    started = time.time()
    try:
        tokenizer = load_tokenizer()
        train_dataset, eval_dataset = load_and_prepare_dataset()
        model = apply_lora(apply_memory_optimizations(load_model()))

        try:
            config = build_training_config(assistant_only=True)
        except (TypeError, ValueError):
            logger.warning(
                "This trl build rejected assistant_only_loss; training on "
                "prompt+completion. Expect the model to over-copy the notes.")
            config = build_training_config(assistant_only=False)

        trainer = SFTTrainer(
            model=model,
            args=config,
            train_dataset=train_dataset,
            eval_dataset=eval_dataset,
            processing_class=tokenizer,
            # No formatting_func: the dataset is conversational, so SFTTrainer
            # applies the chat template itself. That is what makes
            # assistant_only_loss possible and keeps training on the exact
            # prompt main.py will build at inference.
        )

        logger.info("Starting training%s...", " (resuming)" if resume else "")
        result = trainer.train(resume_from_checkpoint=resume)
        logger.info("Training complete: %s", result.metrics)

        eval_metrics = trainer.evaluate()
        logger.info("Eval: %s", eval_metrics)

        OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
        trainer.model.save_pretrained(str(OUTPUT_DIR))
        tokenizer.save_pretrained(str(OUTPUT_DIR))
        (OUTPUT_DIR / "train_metrics.json").write_text(
            json.dumps(result.metrics, indent=2), encoding="utf-8")
        (OUTPUT_DIR / "eval_metrics.json").write_text(
            json.dumps(eval_metrics, indent=2), encoding="utf-8")

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
