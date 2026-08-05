import torch
import transformers
import datasets
import peft
import trl
import accelerate

print("=" * 50)
print("Environment Verification")
print("=" * 50)

print(f"PyTorch Version: {torch.__version__}")
print(f"Transformers Version: {transformers.__version__}")
print(f"Datasets Version: {datasets.__version__}")
print(f"PEFT Version: {peft.__version__}")
print(f"TRL Version: {trl.__version__}")
print(f"Accelerate Version: {accelerate.__version__}")

print()

print("CUDA Available:", torch.cuda.is_available())

if torch.cuda.is_available():
    print("GPU:", torch.cuda.get_device_name(0))
    print(
        "GPU Memory:",
        round(torch.cuda.get_device_properties(0).total_memory / (1024**3), 2),
        "GB",
    )

print("\nEverything is ready!")