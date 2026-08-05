from transformers import AutoConfig


model_path="../models/Llama-3.1-8B-Instruct"


config = AutoConfig.from_pretrained(model_path)


print("========== MODEL INFORMATION ==========")

print("Architecture:")
print(config.architectures)

print("\nHidden Size:")
print(config.hidden_size)

print("\nNumber of Layers:")
print(config.num_hidden_layers)

print("\nAttention Heads:")
print(config.num_attention_heads)

print("\nVocabulary Size:")
print(config.vocab_size)

print("\nMaximum Sequence Length:")
print(config.max_position_embeddings)