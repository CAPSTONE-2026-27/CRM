import torch
from transformers import AutoTokenizer, AutoModelForCausalLM


model_path = "./models/Llama-3.1-8B-Instruct"


print("Loading tokenizer...")

tokenizer = AutoTokenizer.from_pretrained(
    model_path
)


print("Loading model...")


model = AutoModelForCausalLM.from_pretrained(
    model_path,
    torch_dtype=torch.float16,
    device_map="auto"
)


print("Model loaded successfully")

print("GPU:")
print(torch.cuda.get_device_name(0))


prompt = "Explain CRM system in simple words"


inputs = tokenizer(
    prompt,
    return_tensors="pt"
).to("cuda")


output = model.generate(
    **inputs,
    max_new_tokens=100
)


print(
    tokenizer.decode(
        output[0],
        skip_special_tokens=True
    )
)