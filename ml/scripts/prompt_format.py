"""
prompt_format.py
================
Single source of truth for the instruction text, system prompt, and Llama 3.1
chat template shared by train.py and inference.py.

Why this module exists: previously train.py and inference.py each hand-wrote
their own version of the prompt. They had drifted apart — inference.py used a
different system prompt (one that explicitly banned "Recommended Action") and
dropped the instruction line from the user turn entirely. That mismatch means
the model was being asked something different at inference time than what it
was trained on, which is a direct cause of inconsistent output regardless of
how clean the dataset is. Importing from here guarantees train and inference
always build the identical prompt.
"""

INSTRUCTION = (
    "Analyze the following lead and predict Lead Score, Qualification, "
    "Priority and Reason."
)

SYSTEM_PROMPT = (
    "You are an AI CRM Lead Management Assistant.\n\n"
    "Analyze the lead details and respond ONLY in this exact format. "
    "Do not add greetings, markdown, or any commentary outside the format below.\n\n"
    "Lead Score: <0-100>/100\n\n"
    "Qualification:\n"
    "<Hot/Warm/Cold>\n\n"
    "Priority:\n"
    "<High/Medium/Low>\n\n"
    "Reason:\n"
    "• <point 1>\n"
    "• <point 2>\n"
    "• <point 3>\n"
    "• <point 4>\n"
    "• <point 5>\n\n"
    "Recommended Action:\n"
    "<one short instruction>"
)


def build_user_turn(lead_input: str) -> str:
    """Combine the fixed instruction with the lead's input fields, exactly
    as every row in train.jsonl does."""
    return f"{INSTRUCTION}\n\n{lead_input}"


def build_llama3_prompt(system: str, user: str, assistant: str = "") -> str:
    """Build one example in raw Llama 3.1 Instruct chat format.

    At training time `assistant` is the target output and the trailing
    <|eot_id|> teaches the model where to stop. At inference time `assistant`
    is left empty so the prompt ends right after the assistant header and
    generation starts from there — no trailing <|eot_id|> in that case.
    """
    prompt = (
        "<|begin_of_text|>"
        "<|start_header_id|>system<|end_header_id|>\n\n"
        f"{system}"
        "<|eot_id|>"
        "<|start_header_id|>user<|end_header_id|>\n\n"
        f"{user}"
        "<|eot_id|>"
        "<|start_header_id|>assistant<|end_header_id|>\n\n"
        f"{assistant}"
    )
    if assistant:
        prompt += "<|eot_id|>"
    return prompt
