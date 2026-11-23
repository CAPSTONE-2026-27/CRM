"""
train.py
========
QLoRA supervised fine-tuning of Llama 3.1 8B Instruct for Marketing Automation
(Module 11): per-lead campaign evaluation and grounded campaign copy, one adapter.

Hardware target : NVIDIA RTX A4000 (16GB VRAM), Windows 11
Base weights    : ai/base-model, shared with the other three adapters; this trains only an adapter

Run (the model server must be STOPPED first; the base model and a training
run do not both fit on a 16GB card):
    python adapters/marketing/data/generate_dataset.py leads --rows 700
    python adapters/marketing/data/generate_dataset.py content --rows 320   # server running
    python adapters/marketing/data/generate_dataset.py build
    python adapters/marketing/train.py
    python adapters/marketing/train.py --resume                              # after a crash

Configuration, and why
----------------------
Carried over unchanged from adapters/deal_state/train.py, where each value was
tuned on this GPU: 4-bit NF4 with double quantisation, r=16 / alpha=32 LoRA on
all attention and MLP projections, paged AdamW 8-bit, lr 1e-4 cosine, effective
batch 8, gradient checkpointing, completion-only loss.

What differs:
  * 3 epochs, not 5. Half the targets are distilled from the base model, so the
    adapter is refining behaviour the base model already has rather than
    learning a new mapping; more epochs would start memorising the 320 briefs'
    wording.
  * Rows longer than max_length are DROPPED, not truncated. A clipped target
    teaches the model to stop mid-object, which surfaces later as "invalid
    JSON" with no hint that the data was the cause.
  * Two tasks in one file. The eval split is stratified by task, so the eval
    loss can't be carried by whichever task happens to dominate the held-out slice.
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
from datasets import concatenate_datasets, load_dataset
from peft import LoraConfig, get_peft_model
from transformers import AutoModelForCausalLM, AutoTokenizer, BitsAndBytesConfig
from trl import SFTConfig, SFTTrainer

sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))
from adapters.marketing import prompt_format as fmt  # noqa: E402

ADAPTER_DIR = Path(__file__).resolve().parent
AI_ROOT = ADAPTER_DIR.parent.parent
MODEL_PATH = Path(os.getenv("CRM_BASE_MODEL", AI_ROOT / "base-model" / "Llama-3.1-8B-Instruct"))
DATA_PATH = ADAPTER_DIR / "data" / "train.jsonl"
# Real review decisions from data/collect_feedback.py, and the rows reserved for
# comparing adapters. Both optional: without them this trains exactly as before.
FEEDBACK_PATH = ADAPTER_DIR / "data" / "feedback.jsonl"
BENCHMARK_PATH = ADAPTER_DIR / "data" / "benchmark.jsonl"
# tools/retrain.py points this at a candidate directory so the live adapter in
# weights/ is untouched until the candidate has been scored.
OUTPUT_DIR = Path(os.getenv("CRM_MARKETING_OUTPUT_DIR", ADAPTER_DIR / "weights"))
LOG_DIR = ADAPTER_DIR / "logs"

SEED = 42
EVAL_FRACTION = 0.10
MAX_LENGTH = 2048
TASKS = ("lead_evaluation", "content_generation")

LOG_DIR.mkdir(parents=True, exist_ok=True)
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s | %(levelname)-8s | %(message)s",
    handlers=[
        logging.StreamHandler(sys.stdout),
        logging.FileHandler(LOG_DIR / "train.log", encoding="utf-8"),
    ],
)
logger = logging.getLogger("marketing_train")


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
def _is_valid(row: dict) -> bool:
    """A usable row: system/user/assistant, and a target the backend would accept."""
    messages = row.get("messages")
    if not messages or len(messages) != 3 or messages[-1].get("role") != "assistant":
        return False
    target = fmt.extract_object(messages[-1]["content"])
    if target is None:
        return False
    if row.get("task") == "lead_evaluation":
        return "lead_id" in target and "score" in target and "eligibility" in target
    return row.get("task") == "content_generation" and len(target) > 0


def benchmark_key(messages: list) -> str:
    return messages[1]["content"]


def split_by_task(dataset):
    """Stratified split: EVAL_FRACTION of each task held out, same seed as eval/evaluate.py."""
    trains, evals = [], []
    for task in TASKS:
        part = dataset.filter(lambda r, t=task: r["task"] == t)
        if len(part) == 0:
            raise ValueError(f"No '{task}' rows in {DATA_PATH}; re-run generate_dataset.py build")
        split = part.train_test_split(test_size=EVAL_FRACTION, seed=SEED, shuffle=True)
        trains.append(split["train"])
        evals.append(split["test"])
    return (concatenate_datasets(trains).shuffle(seed=SEED), concatenate_datasets(evals))


def load_and_prepare_dataset(tokenizer):
    if not DATA_PATH.exists():
        raise FileNotFoundError(
            f"No training data at {DATA_PATH}. Run generate_dataset.py leads / content / build first."
        )
    files = [str(DATA_PATH)]
    if FEEDBACK_PATH.exists() and FEEDBACK_PATH.stat().st_size > 0:
        files.append(str(FEEDBACK_PATH))
    dataset = load_dataset("json", data_files=files, split="train")
    logger.info("Raw dataset: %d rows from %s", len(dataset), ", ".join(Path(f).name for f in files))

    # A benchmark row must never be trained on, or comparing adapters on it
    # measures memorisation. Matched on the user prompt: the same input with a
    # different answer is still the same question.
    if BENCHMARK_PATH.exists():
        reserved = {benchmark_key(json.loads(line)["messages"])
                    for line in BENCHMARK_PATH.open(encoding="utf-8") if line.strip()}
        before = len(dataset)
        dataset = dataset.filter(lambda r: benchmark_key(r["messages"]) not in reserved)
        logger.info("Excluded %d rows that are in %s", before - len(dataset), BENCHMARK_PATH.name)

    before = len(dataset)
    dataset = dataset.filter(_is_valid)
    if len(dataset) != before:
        logger.warning("Dropped %d rows with an unusable target", before - len(dataset))

    def token_length(row):
        text = tokenizer.apply_chat_template(row["messages"], tokenize=False)
        return {"n_tokens": len(tokenizer(text, add_special_tokens=False)["input_ids"])}

    dataset = dataset.map(token_length)
    lengths = sorted(dataset["n_tokens"])
    logger.info("Token lengths: min %d, median %d, p99 %d, max %d",
                lengths[0], lengths[len(lengths) // 2], lengths[int(len(lengths) * 0.99) - 1], lengths[-1])
    before = len(dataset)
    dataset = dataset.filter(lambda r: r["n_tokens"] <= MAX_LENGTH)
    if len(dataset) != before:
        logger.warning("Dropped %d rows longer than %d tokens", before - len(dataset), MAX_LENGTH)

    train, evaluation = split_by_task(dataset)
    for name, part in (("train", train), ("eval", evaluation)):
        counts = {t: sum(1 for x in part["task"] if x == t) for t in TASKS}
        logger.info("%s: %d rows %s", name, len(part), counts)
    # The exact held-out rows, for eval/evaluate.py: scoring the adapter on rows
    # it trained on would measure memorisation, not the task.
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    with (OUTPUT_DIR / "heldout.jsonl").open("w", encoding="utf-8") as out:
        for row in evaluation:
            out.write(json.dumps({k: row[k] for k in ("task", "messages", "meta")}, ensure_ascii=False) + "\n")
    # Only the conversation goes to the trainer.
    keep = ["messages"]
    return (train.remove_columns([c for c in train.column_names if c not in keep]),
            evaluation.remove_columns([c for c in evaluation.column_names if c not in keep]))


# --------------------------------------------------------------------------
# MODEL
# --------------------------------------------------------------------------
def load_tokenizer():
    logger.info("Loading tokenizer: %s", MODEL_PATH)
    tokenizer = AutoTokenizer.from_pretrained(str(MODEL_PATH), trust_remote_code=True, local_files_only=True)
    if tokenizer.pad_token is None:
        tokenizer.pad_token = tokenizer.eos_token
        tokenizer.pad_token_id = tokenizer.eos_token_id
    tokenizer.padding_side = "right"
    # This adapter's template (no date preamble, {% generation %} markers).
    # server/main.py renders marketing prompts with the identical string.
    fmt.apply_chat_template(tokenizer)
    return tokenizer


def load_model():
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
    model.config.use_cache = False
    # Not prepare_model_for_kbit_training: it also upcasts the embedding table
    # and lm_head to fp32 (+2GB weights, +3GB peak measured) for layers LoRA
    # never trains. See adapters/lead_scoring/train.py for the measurement.
    for param in model.parameters():
        param.requires_grad_(False)
    model.enable_input_require_grads()
    model.gradient_checkpointing_enable(gradient_checkpointing_kwargs={"use_reentrant": False})
    return model


def apply_lora(model):
    config = LoraConfig(
        r=16,
        lora_alpha=32,
        lora_dropout=0.05,
        bias="none",
        task_type="CAUSAL_LM",
        target_modules=["q_proj", "k_proj", "v_proj", "o_proj", "gate_proj", "up_proj", "down_proj"],
    )
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
        num_train_epochs=3,
        optim="paged_adamw_8bit",
        lr_scheduler_type="cosine",
        warmup_ratio=0.03,
        logging_steps=5,
        eval_strategy="steps",
        eval_steps=40,
        save_steps=40,        # must stay a multiple of eval_steps for load_best_model_at_end
        save_strategy="steps",
        save_total_limit=2,
        load_best_model_at_end=True,
        metric_for_best_model="eval_loss",
        greater_is_better=False,
        bf16=bf16_ok,
        fp16=not bf16_ok,
        max_length=MAX_LENGTH,
        packing=False,
        gradient_checkpointing=True,
        gradient_checkpointing_kwargs={"use_reentrant": False},
        report_to="none",
        seed=SEED,
        dataset_num_proc=1,
        dataloader_num_workers=0,
        remove_unused_columns=True,
        logging_dir=str(LOG_DIR),
        **kwargs,
    )


def main() -> None:
    logger.info("=" * 70)
    logger.info("CRM Marketing Automation — QLoRA fine-tuning")
    logger.info("transformers=%s | torch=%s", transformers.__version__, torch.__version__)
    logger.info("=" * 70)
    set_seed(SEED)

    if not torch.cuda.is_available():
        raise RuntimeError("CUDA GPU not detected; 4-bit QLoRA training requires one.")
    logger.info("GPU: %s", torch.cuda.get_device_name(0))
    free, total = torch.cuda.mem_get_info()
    if free < 11 * 1024 ** 3:
        logger.warning("Only %.1f of %.1f GB VRAM free. Stop the model server (server/main.py) "
                       "before training, or this run will run out of memory.", free / 1024 ** 3, total / 1024 ** 3)

    resume = "--resume" in sys.argv
    if resume and not any(OUTPUT_DIR.glob("checkpoint-*")):
        raise FileNotFoundError(f"--resume given but no checkpoint-* in {OUTPUT_DIR}.")

    started = time.time()
    try:
        tokenizer = load_tokenizer()
        train_dataset, eval_dataset = load_and_prepare_dataset(tokenizer)
        model = apply_lora(apply_memory_optimizations(load_model()))

        try:
            config = build_training_config(assistant_only=True)
        except (TypeError, ValueError):
            logger.warning("This trl build rejected assistant_only_loss; training on prompt+completion.")
            config = build_training_config(assistant_only=False)

        trainer = SFTTrainer(
            model=model,
            args=config,
            train_dataset=train_dataset,
            eval_dataset=eval_dataset,
            processing_class=tokenizer,
        )

        logger.info("Starting training%s...", " (resuming)" if resume else "")
        result = trainer.train(resume_from_checkpoint=resume)
        logger.info("Training complete: %s", result.metrics)

        eval_metrics = trainer.evaluate()
        logger.info("Eval: %s", eval_metrics)

        OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
        trainer.model.save_pretrained(str(OUTPUT_DIR))
        tokenizer.save_pretrained(str(OUTPUT_DIR))
        (OUTPUT_DIR / "train_metrics.json").write_text(json.dumps(result.metrics, indent=2), encoding="utf-8")
        (OUTPUT_DIR / "eval_metrics.json").write_text(json.dumps(eval_metrics, indent=2), encoding="utf-8")
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
