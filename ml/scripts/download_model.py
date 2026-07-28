from transformers import AutoTokenizer, AutoModelForCausalLM
import torch

model_id = "meta-llama/Llama-3.1-8B-Instruct"

print("Downloading tokenizer...")

tokenizer = AutoTokenizer.from_pretrained(model_id)

print("Downloading model...")

model = AutoModelForCausalLM.from_pretrained(
    model_id,
    torch_dtype=torch.bfloat16,
    device_map="auto"
)

print("Model loaded successfully!")

print("GPU Used:")
print(torch.cuda.get_device_name(0))