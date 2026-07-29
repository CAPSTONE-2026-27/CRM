"""
train.py
========
QLoRA Supervised Fine-Tuning of Llama 3.1 8B Instruct
for a CRM AI Lead Management Assistant.

Hardware target : NVIDIA RTX A4000 (16GB VRAM), Windows 11, CUDA 12.8
Library versions: transformers==5.14.1, trl==1.8.0, peft==0.19.1,
                   datasets==5.0.0, accelerate==1.14.0,
                   bitsandbytes==0.49.2, torch==2.13.0+cu126

Run:
    python scripts/train.py
"""

import os
import sys
import json
import time
import random
import logging
import traceback
from pathlib import Path

import torch
import numpy as np
import transformers
from datasets import load_dataset
from transformers import (
    AutoTokenizer,
    AutoModelForCausalLM,
    BitsAndBytesConfig,
)
from peft import (
    LoraConfig,
    get_peft_model,
    prepare_model_for_kbit_training,
)
from trl import SFTTrainer, SFTConfig

sys.path.insert(0, str(Path(__file__).resolve().parent))
from prompt_format import SYSTEM_PROMPT, build_llama3_prompt  # noqa: E402


# --------------------------------------------------------------------------
# 1. PATHS
# --------------------------------------------------------------------------
PROJECT_ROOT = Path(__file__).resolve().parent.parent
MODEL_PATH = PROJECT_ROOT / "models" / "Llama-3.1-8B-Instruct"
DATA_PATH = PROJECT_ROOT / "data" / "train.jsonl"
OUTPUT_DIR = PROJECT_ROOT / "outputs" / "lead_management_llama3_lora"
LOG_DIR = PROJECT_ROOT / "outputs" / "logs"

SEED = 42


# --------------------------------------------------------------------------
# 2. LOGGING
# --------------------------------------------------------------------------
LOG_DIR.mkdir(parents=True, exist_ok=True)
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s | %(levelname)-8s | %(message)s",
    handlers=[
        logging.StreamHandler(sys.stdout),
        logging.FileHandler(LOG_DIR / "train.log", encoding="utf-8"),
    ],
)
logger = logging.getLogger("crm_lora_train")


# --------------------------------------------------------------------------
# 3. REPRODUCIBILITY
# --------------------------------------------------------------------------
def set_seed(seed: int = SEED) -> None:
    """Fix all relevant RNGs so runs are reproducible."""
    random.seed(seed)
    np.random.seed(seed)
    torch.manual_seed(seed)
    torch.cuda.manual_seed_all(seed)
    # Deterministic cuDNN kernels where possible. Note: full determinism on
    # 4-bit quantized kernels is not guaranteed by bitsandbytes, but this
    # keeps everything else (dataloader shuffling, dropout, init) fixed.
    torch.backends.cudnn.deterministic = True
    torch.backends.cudnn.benchmark = False
    os.environ["PYTHONHASHSEED"] = str(seed)


# --------------------------------------------------------------------------
# 4. CHAT TEMPLATE FORMATTING
# --------------------------------------------------------------------------
# build_llama3_prompt and SYSTEM_PROMPT now live in prompt_format.py, imported
# above, so train.py and inference.py can never drift onto different prompts.


def formatting_func(example: dict) -> str:
    """
    SFTTrainer formatting_func: combines instruction + input + output into
    the Llama 3.1 chat template for a single example. The installed trl
    version calls this per-row and expects a single string back (not a
    batched list), so this must not use zip()/loop-over-columns logic.

    Missing / malformed fields are handled defensively: rows that cannot be
    turned into a valid (user_content, output) pair return an empty string.
    In practice this should never trigger here since `clean_dataset` already
    filters out invalid rows before the dataset reaches the trainer.
    """
    instruction = (example.get("instruction") or "").strip()
    inp = (example.get("input") or "").strip()
    output = (example.get("output") or "").strip()

    if not instruction or not output:
        return ""

    user = f"{instruction}\n\n{inp}" if inp else instruction

    return build_llama3_prompt(SYSTEM_PROMPT, user, output)


def clean_dataset(dataset):
    """Remove rows where instruction or output is missing/empty."""
    def _is_valid(row):
        instruction = (row.get("instruction") or "").strip()
        output = (row.get("output") or "").strip()
        return bool(instruction) and bool(output)

    before = len(dataset)
    dataset = dataset.filter(_is_valid)
    after = len(dataset)
    if before != after:
        logger.warning(
            "Filtered out %d invalid rows (missing instruction/output). "
            "%d valid rows remain.",
            before - after,
            after,
        )
    return dataset


# --------------------------------------------------------------------------
# 5. TOKENIZER
# --------------------------------------------------------------------------
def load_tokenizer():
    logger.info("Loading tokenizer from local path: %s", MODEL_PATH)
    tokenizer = AutoTokenizer.from_pretrained(
        str(MODEL_PATH),
        trust_remote_code=True,
        local_files_only=True,
    )

    # Llama 3.1 has no pad token by default. Reuse the eos token as pad so
    # padding tokens are never confused with real content, and set the
    # padding side to "right" which is correct for causal-LM SFT
    # (left-padding is only needed for batched generation).
    if tokenizer.pad_token is None:
        tokenizer.pad_token = tokenizer.eos_token
        tokenizer.pad_token_id = tokenizer.eos_token_id
    tokenizer.padding_side = "right"

    return tokenizer


# --------------------------------------------------------------------------
# 6. MODEL + 4-BIT QUANTIZATION
# --------------------------------------------------------------------------
def load_model():
    logger.info("Building BitsAndBytesConfig for 4-bit QLoRA loading.")
    # 4-bit NF4 quantization is required here because a full-precision or
    # even fp16 8B model (~16GB+ in fp16) will not fit in 16GB of VRAM
    # together with optimizer states, gradients, and activations.
    # - nf4: information-theoretically optimal 4-bit datatype for
    #   normally-distributed weights (better than plain int4/fp4).
    # - bfloat16 compute dtype: matches Ampere's native bf16 tensor cores
    #   on the RTX A4000 and avoids fp16 overflow issues.
    # - double quant: quantizes the quantization constants themselves,
    #   saving an additional ~0.4 bits/parameter with negligible quality
    #   loss, which matters at 16GB VRAM.
    bnb_config = BitsAndBytesConfig(
        load_in_4bit=True,
        bnb_4bit_quant_type="nf4",
        bnb_4bit_compute_dtype=torch.bfloat16,
        bnb_4bit_use_double_quant=True,
    )

    compute_dtype = (
        torch.bfloat16 if torch.cuda.is_bf16_supported() else torch.float16
    )
    logger.info("Loading base model from local path: %s", MODEL_PATH)
    model = AutoModelForCausalLM.from_pretrained(
        str(MODEL_PATH),
        quantization_config=bnb_config,
        device_map="auto",
        torch_dtype=compute_dtype,
        trust_remote_code=True,
        local_files_only=True,
    )

    return model


def apply_memory_optimizations(model):
    """Gradient checkpointing + kbit training prep for 16GB VRAM."""
    logger.info("Applying memory optimizations for 16GB VRAM budget.")

    model.config.use_cache = False  # incompatible with gradient checkpointing

    # prepare_model_for_kbit_training casts norm layers to fp32 for
    # stability, enables input gradients for checkpointing to work through
    # a frozen/quantized base model, and freezes base weights.
    model = prepare_model_for_kbit_training(
        model,
        use_gradient_checkpointing=True,
        gradient_checkpointing_kwargs={"use_reentrant": False},
    )
    model.gradient_checkpointing_enable(gradient_checkpointing_kwargs={"use_reentrant": False})

    return model


def apply_lora(model):
    logger.info("Attaching LoRA adapters.")
    lora_config = LoraConfig(
        r=16,
        lora_alpha=32,
        lora_dropout=0.05,
        bias="none",
        task_type="CAUSAL_LM",
        target_modules=[
            "q_proj",
            "k_proj",
            "v_proj",
            "o_proj",
            "gate_proj",
            "up_proj",
            "down_proj",
        ],
    )
    model = get_peft_model(model, lora_config)
    model.print_trainable_parameters()
    return model


# --------------------------------------------------------------------------
# 7. DATASET
# --------------------------------------------------------------------------
EVAL_FRACTION = 0.10


def load_and_prepare_dataset():
    """Load train.jsonl and split off a held-out eval slice.

    Every run before this one trained with no eval_dataset at all, so there
    was no signal distinguishing "learned the scoring policy" from
    "memorized the training rows" — the single highest-priority fix for
    training quality. The split is seeded and shuffled before splitting so
    it's reproducible and not just the last N rows (which, if the generator
    ever writes examples in a non-random order, would bias the split).
    """
    if not DATA_PATH.exists():
        raise FileNotFoundError(f"Training data not found at: {DATA_PATH}")

    logger.info("Loading dataset from: %s", DATA_PATH)

    dataset = load_dataset(
        "json",
        data_files=str(DATA_PATH),
        split="train"
    )

    logger.info("Raw dataset size: %d rows", len(dataset))

    dataset = clean_dataset(dataset)

    logger.info("Clean dataset size: %d rows", len(dataset))

    split = dataset.train_test_split(test_size=EVAL_FRACTION, seed=SEED, shuffle=True)
    train_dataset, eval_dataset = split["train"], split["test"]

    logger.info(
        "Train/eval split: %d train rows, %d eval rows (%.0f%% held out)",
        len(train_dataset), len(eval_dataset), EVAL_FRACTION * 100,
    )

    return train_dataset, eval_dataset


# --------------------------------------------------------------------------
# 8. TRAINING ARGUMENTS (SFTConfig)
# --------------------------------------------------------------------------
def build_training_config() -> SFTConfig:
    bf16_ok = torch.cuda.is_bf16_supported()
    logger.info("bf16 supported by GPU: %s", bf16_ok)

    # In trl>=0.10 (and 1.x), SFTConfig replaces TrainingArguments as the
    # single source of truth for both HF Trainer args AND SFT-specific
    # args (max_length, packing, dataset_text_field, etc.). Passing a
    # plain TrainingArguments object is deprecated.
    config = SFTConfig(
        output_dir=str(OUTPUT_DIR),
        per_device_train_batch_size=1,
        per_device_eval_batch_size=1,
        gradient_accumulation_steps=8,
        learning_rate=1e-4,
        # A 3-epoch run was tried here and measured to be wrong: aggregate
        # eval_loss looked converged by epoch ~2, but eval_loss is dominated
        # by the long, easy-to-predict boilerplate reasoning text (longer
        # after grounding each bullet in the lead's actual numbers). The
        # decision-critical tokens -- Qualification, Priority, exact score --
        # are a small fraction of that text and measurably needed more
        # passes: held-out Qualification accuracy dropped from 96% (8
        # epochs, shorter reasoning) to 90% (3 epochs, grounded reasoning),
        # and exact Lead Score match dropped from 72% to 44%. Back to 8.
        num_train_epochs=8,
        optim="paged_adamw_8bit",
        lr_scheduler_type="cosine",
        warmup_ratio=0.03,
        logging_steps=5,
        eval_strategy="steps",    # was previously unset: training ran with zero eval signal
        # eval_steps=10 meant a full 50-example eval pass (~13s) every 10
        # training steps -- ~45 extra evals over a full run, on the order of
        # 10 minutes of pure overhead. 20 halves that while still giving
        # frequent enough eval_loss signal for load_best_model_at_end.
        eval_steps=20,
        save_steps=20,           # must stay a round multiple of eval_steps for load_best_model_at_end
        save_strategy="steps",
        save_total_limit=2,
        load_best_model_at_end=True,
        metric_for_best_model="eval_loss",
        greater_is_better=False,
        bf16=bf16_ok,
        fp16=not bf16_ok,
        max_length=2048,          # sequence length cap (formerly max_seq_length)
        packing=False,            # keep examples separate: clearer loss signal for structured CRM outputs
        gradient_checkpointing=True,
        gradient_checkpointing_kwargs={"use_reentrant": False},
        report_to="none",
        seed=SEED,
        dataset_num_proc=1,       # Windows: avoid multiprocessing dataloader issues
        dataloader_num_workers=0, # Windows: avoid multiprocessing dataloader issues
        remove_unused_columns=False,  # required so formatting_func can see raw columns
        logging_dir=str(LOG_DIR),
    )
    return config


# --------------------------------------------------------------------------
# 9. MAIN
# --------------------------------------------------------------------------
def main():
    logger.info("=" * 70)
    logger.info("CRM Lead Management Assistant - QLoRA Fine-Tuning")
    logger.info("transformers=%s | torch=%s", transformers.__version__, torch.__version__)
    logger.info("=" * 70)

    set_seed(SEED)

    if not torch.cuda.is_available():
        raise RuntimeError(
            "CUDA GPU not detected. This script requires an NVIDIA GPU "
            "(RTX A4000, 16GB VRAM) for 4-bit QLoRA training."
        )
    logger.info("GPU detected: %s", torch.cuda.get_device_name(0))

    start_time = time.time()

    try:
        # --- Tokenizer ---
        tokenizer = load_tokenizer()

        # --- Dataset ---
        train_dataset, eval_dataset = load_and_prepare_dataset()

        # --- Model ---
        model = load_model()
        model = apply_memory_optimizations(model)
        model = apply_lora(model)

        # --- Training config ---
        sft_config = build_training_config()

        # --- Trainer ---
        logger.info("Initializing SFTTrainer.")
        trainer = SFTTrainer(
            model=model,
            args=sft_config,
            train_dataset=train_dataset,
            eval_dataset=eval_dataset,
            processing_class=tokenizer,
            formatting_func=formatting_func,
        )

        # --- Train ---
        logger.info("Starting training...")
        train_result = trainer.train()
        logger.info("Training complete. Metrics: %s", train_result.metrics)

        # --- Eval (best checkpoint, since load_best_model_at_end=True) ---
        logger.info("Evaluating best checkpoint on held-out eval split...")
        eval_metrics = trainer.evaluate()
        logger.info("Eval metrics: %s", eval_metrics)

        # --- Save ---
        OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
        logger.info("Saving LoRA adapter to: %s", OUTPUT_DIR)
        trainer.model.save_pretrained(str(OUTPUT_DIR))
        tokenizer.save_pretrained(str(OUTPUT_DIR))

        # Persist training + eval metrics for later reference.
        with open(OUTPUT_DIR / "train_metrics.json", "w", encoding="utf-8") as f:
            json.dump(train_result.metrics, f, indent=2)
        with open(OUTPUT_DIR / "eval_metrics.json", "w", encoding="utf-8") as f:
            json.dump(eval_metrics, f, indent=2)

        elapsed = time.time() - start_time
        logger.info("Total training time: %.2f minutes", elapsed / 60)
        logger.info("Adapter, tokenizer, and metrics saved successfully.")

    except Exception:
        logger.error("Training failed with an exception:\n%s", traceback.format_exc())
        raise

    finally:
        # Always attempt to free GPU memory, even on failure.
        if torch.cuda.is_available():
            torch.cuda.empty_cache()
            torch.cuda.synchronize()
        logger.info("CUDA memory cleanup complete.")


if __name__ == "__main__":
    main()
