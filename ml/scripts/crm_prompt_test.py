from transformers import AutoTokenizer, AutoModelForCausalLM
import torch


model_path="./models/Llama-3.1-8B-Instruct"


print("Loading tokenizer...")

tokenizer = AutoTokenizer.from_pretrained(model_path)


print("Loading model...")

model = AutoModelForCausalLM.from_pretrained(
    model_path,
    device_map="auto",
    dtype=torch.float16
)


print("Model loaded successfully")


prompts = [

    "Explain lead management module in CRM system",

    "Write a professional follow up email for a customer who has not responded",

    "A customer says their insurance claim is delayed. Analyze the complaint and suggest resolution",

    "Generate a sales performance summary report for a CRM dashboard"

]


for prompt in prompts:

    print("\n==============================")
    print("PROMPT:")
    print(prompt)


    inputs = tokenizer(
        prompt,
        return_tensors="pt"
    ).to(model.device)


    output = model.generate(
        **inputs,
        max_new_tokens=200,
        temperature=0.7
    )


    response = tokenizer.decode(
        output[0],
        skip_special_tokens=True
    )


    print("\nRESPONSE:")
    print(response)