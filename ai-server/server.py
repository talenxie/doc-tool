"""
LLaVA-7B 本地 AI 服务 - 破甲版
通过 LogitsProcessor 屏蔽拒绝词，永不拒绝
"""
import os
os.environ["HF_HOME"] = "D:\\workTools\\huggingface"

import sys
import io
import json
import re
import base64
import urllib.request
import urllib.parse
import torch
from PIL import Image
from flask import Flask, request
from transformers import (
    LlavaForConditionalGeneration,
    LlavaProcessor,
    BitsAndBytesConfig,
    LogitsProcessor,
    LogitsProcessorList,
)

app = Flask(__name__)

# ========== 拒绝处理（定点破甲） ==========
# 旧版把拒绝短语的"全部 token"全局屏蔽，"I"/" to"/"not"/" can" 等高频词也被禁掉，
# 生成的英文残缺、指令跟随能力崩坏（年龄等要求全部丢失）。
# 现改为：第一遍干净生成 → 输出开头命中拒绝话术才重试一次，且只在生成的前几个
# token 位置屏蔽拒绝短语的"起始 token"（不影响正文用词）→ 仍拒绝则返回空串，
# 由 Java 端回退 Ollama。
REFUSAL_RE = re.compile(
    r"(\b(i'?m\s+sorry|sorry|i\s+apologize|i\s+cannot|i\s+can\s+not|i\s+can'?t|"
    r"i'?m\s+unable|i\s+am\s+unable|unable\s+to|i\s+must\s+decline|i\s+will\s+not|"
    r"i\s+won'?t|unfortunately|as\s+an?\s+ai|as\s+a\s+vision\s+model|"
    r"cannot\s+provide|can'?t\s+provide|not\s+able\s+to|"
    r"not\s+be\s+(appropriate|suitable)|inappropriate|offensive|harmful)\b)",
    re.IGNORECASE,
)

REFUSAL_OPENERS = [
    "I'm sorry", "I am sorry", "Sorry", "I apologize",
    "I cannot", "I can not", "I can't",
    "I'm unable", "I am unable", "Unable",
    "I must decline", "I will not", "I won't",
    "Unfortunately", "As an AI", "As a vision model",
]


def looks_like_refusal(text):
    return bool(REFUSAL_RE.search(text.strip()[:120]))


class StartRefusalSuppressor(LogitsProcessor):
    """仅在前 window 个生成位置屏蔽拒绝短语的起始 token，正文用词不受影响。"""

    def __init__(self, tokenizer, prompt_len, window=6):
        self.prompt_len = prompt_len
        self.window = window
        self.blocked_ids = set()
        for phrase in REFUSAL_OPENERS:
            for variant in (phrase, " " + phrase):
                ids = tokenizer.encode(variant, add_special_tokens=False)
                if ids:
                    self.blocked_ids.add(ids[0])

    def __call__(self, input_ids, scores):
        step = input_ids.shape[-1] - self.prompt_len
        if 0 <= step < self.window:
            for tid in self.blocked_ids:
                scores[:, tid] = -float("Inf")
        return scores


# ========== 加载模型 ==========
DEFAULT_MODEL = "llava-hf/llava-1.5-7b-hf"
current_model_name = DEFAULT_MODEL

quant_config = BitsAndBytesConfig(
    load_in_4bit=True,
    bnb_4bit_compute_dtype=torch.float16,
    bnb_4bit_use_double_quant=True,
    bnb_4bit_quant_type="nf4",
    llm_int8_enable_fp32_cpu_offload=True,
)

processor = None
model = None


def load_model(model_name):
    global processor, model, current_model_name
    if model_name == current_model_name and model is not None:
        return
    print(f"Loading model: {model_name} (4-bit quantized)...")
    if model is not None:
        del model
        del processor
        torch.cuda.empty_cache()
    processor = LlavaProcessor.from_pretrained(model_name, local_files_only=True)
    model = LlavaForConditionalGeneration.from_pretrained(
        model_name,
        quantization_config=quant_config,
        device_map="auto",
        max_memory={0: "5GiB", "cpu": "16GiB"},
        local_files_only=True,
    )
    model.eval()
    current_model_name = model_name
    print(f"Model loaded: {model_name}  GPU: {torch.cuda.memory_allocated()/1024**3:.1f} GB")


load_model(DEFAULT_MODEL)


# ========== 生成描述 ==========
def describe_image(image, prompt):
    conversation = [
        {
            "role": "user",
            "content": [
                {"type": "image"},
                {"type": "text", "text": prompt},
            ],
        }
    ]
    prompt_text = processor.apply_chat_template(conversation, add_generation_prompt=True)
    inputs = processor(images=image, text=prompt_text, return_tensors="pt").to(model.device)
    input_len = inputs["input_ids"].shape[1]

    def run(suppressor=None):
        gen_kwargs = dict(max_new_tokens=512, do_sample=False)
        if suppressor is not None:
            gen_kwargs["logits_processor"] = LogitsProcessorList([suppressor])
        with torch.no_grad():
            output = model.generate(**inputs, **gen_kwargs)
        return processor.tokenizer.decode(output[0][input_len:], skip_special_tokens=True).strip()

    # 第一遍干净生成（保证语言流畅、指令跟随）；命中拒绝才定点重试
    text = run()
    if looks_like_refusal(text):
        text = run(StartRefusalSuppressor(processor.tokenizer, input_len))
        if looks_like_refusal(text):
            print("Refusal persisted after retry; returning empty for fallback")
            return ""
    return text


def translate_to_chinese(text):
    try:
        encoded = urllib.parse.quote(text)
        url = f"https://translate.googleapis.com/translate_a/single?client=gtx&sl=en&tl=zh-CN&dt=t&q={encoded}"
        req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
        with urllib.request.urlopen(req, timeout=15) as resp:
            body = resp.read().decode("utf-8")
        parts = body.split('],[')
        translated = ""
        for part in parts:
            start = part.find('"')
            if start < 0:
                continue
            end = part.find('","', start + 1)
            if end < 0:
                end = part.find('"]', start + 1)
            if end < 0:
                continue
            segment = part[start + 1:end]
            if any('\u4e00' <= c <= '\u9fff' for c in segment):
                translated += segment
        return translated if translated else text
    except Exception as e:
        print(f"Translation error: {e}")
        return text


# ========== API ==========
@app.route("/api/describe", methods=["POST"])
def describe():
    data = request.json
    if not data:
        return json_response({"error": "No data"}, 400)

    image_b64 = data.get("image")
    prompt = data.get("prompt", "Describe this image in detail.")
    req_model = data.get("model")

    if not image_b64:
        return json_response({"error": "No image"}, 400)

    try:
        # 切换模型
        if req_model and req_model != current_model_name:
            load_model(req_model)

        image_bytes = base64.b64decode(image_b64)
        image = Image.open(io.BytesIO(image_bytes)).convert("RGB")

        # 生成英文描述（带破甲）
        english_desc = describe_image(image, prompt)

        # 翻译成中文
        chinese_desc = translate_to_chinese(english_desc)

        torch.cuda.empty_cache()
        return json_response({"description": chinese_desc, "model": current_model_name})
    except Exception as e:
        torch.cuda.empty_cache()
        return json_response({"error": str(e)}, 500)


@app.route("/api/translate", methods=["POST"])
def translate():
    data = request.json
    if not data:
        return json_response({"error": "No data"}, 400)
    text = data.get("text", "")
    if not text:
        return json_response({"error": "No text"}, 400)
    result = translate_to_chinese(text)
    return json_response({"translated": result})


@app.route("/api/health", methods=["GET"])
def health():
    return json_response({"status": "ok", "model": current_model_name, "jailbreak": True})


@app.route("/api/models", methods=["GET"])
def list_models():
    """列出本地 HuggingFace 缓存中可用的 LLaVA 视觉模型"""
    import glob as glob_mod
    cache_dir = os.environ.get("HF_HOME", os.path.expanduser("~/.cache/huggingface"))
    hub_dir = os.path.join(cache_dir, "hub")
    models = []
    if os.path.isdir(hub_dir):
        for d in os.listdir(hub_dir):
            if not d.startswith("models--"):
                continue
            model_id = d.replace("models--", "").replace("--", "/")
            # 只列出 LLaVA 系列（当前代码只支持 LlavaForConditionalGeneration）
            if "llava" in model_id.lower():
                # 检查是否有 snapshots（说明模型已下载完成）
                snap_dir = os.path.join(hub_dir, d, "snapshots")
                if os.path.isdir(snap_dir) and os.listdir(snap_dir):
                    models.append(model_id)
    if not models:
        models = [current_model_name]
    models.sort()
    return json_response({"models": models, "current": current_model_name})


def json_response(data, status=200):
    return app.response_class(
        response=json.dumps(data, ensure_ascii=False),
        status=status,
        mimetype="application/json",
    )


if __name__ == "__main__":
    print("Starting AI server on port 5001...")
    app.run(host="0.0.0.0", port=5001, debug=False)
