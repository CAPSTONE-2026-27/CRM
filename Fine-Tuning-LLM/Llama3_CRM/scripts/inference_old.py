import torch

from transformers import (
    AutoTokenizer,
    AutoModelForCausalLM,
)

from peft import PeftModel

# ============================================================
# Configuration
# ============================================================

BASE_MODEL = "meta-llama/Llama-3.1-8B-Instruct"

ADAPTER_PATH = "outputs/lead_management_llama3_lora"

DEVICE = "cuda" if torch.cuda.is_available() else "cpu"

print("=" * 60)
print("CRM AI Lead Management Assistant")
print("=" * 60)
print(f"Device : {DEVICE}")
print(f"Base Model : {BASE_MODEL}")
print(f"Adapter Path : {ADAPTER_PATH}")
print("=" * 60)



# ============================================================
# Load Tokenizer
# ============================================================

print("\nLoading tokenizer...")

tokenizer = AutoTokenizer.from_pretrained(BASE_MODEL)

print("Tokenizer loaded successfully.")


# ============================================================
# Load Base Model
# ============================================================

print("\nLoading base model...")

base_model = AutoModelForCausalLM.from_pretrained(
    BASE_MODEL,
    device_map="auto",
    torch_dtype=torch.float16,
)

print("Base model loaded successfully.")

# ============================================================
# Load LoRA Adapter
# ============================================================

print("\nLoading LoRA adapter...")

model = PeftModel.from_pretrained(
    base_model,
    ADAPTER_PATH,
)

print("LoRA adapter loaded successfully.")


# ============================================================
# Merge LoRA with Base Model
# ============================================================

print("\nMerging LoRA adapter with base model...")

model = model.merge_and_unload()

print("Model merged successfully.")

model.eval()




# ============================================================
# Interactive Chat
# ============================================================

print("\n" + "=" * 60)
print("CRM AI Lead Management Assistant")
print("Type 'exit' to quit.")
print("=" * 60)

while True:

    print("\nEnter Lead Details (type 'done' on a new line when finished):")

    lines = []

    while True:
        line = input()

        if line.lower() == "done":
            break

        if line.lower() == "exit":
            print("\nExiting CRM Assistant...")
            exit()

        lines.append(line)

    user_input = "\n".join(lines)

    print("\nTokenizing input...")

    inputs = tokenizer(
        user_input,
        return_tensors="pt"
    ).to(model.device)

    print("Input tokenized successfully.")

    print("Generating response... Please wait.")

    with torch.no_grad():

        outputs = model.generate(
            **inputs,
            max_new_tokens=128,
            temperature=0.2,
            do_sample=False,
            pad_token_id=tokenizer.eos_token_id
        )

    print("Generation completed.")

    response = tokenizer.decode(
        outputs[0],
        skip_special_tokens=True
    )

    print("\n" + "=" * 60)
    print("AI Response")
    print("=" * 60)
    print(response)