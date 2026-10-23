"""ModelSpec: architecture geometry (public HF config.json knowledge) + GGUF byte math.

Geometry confidence is flagged per preset (HIGH = config.json widely reproduced in
finetune repos / papers). Byte-rate constants: exact where derivable from the
gguf k-quant block layout (see llama.cpp ggml-quants.h), ESTIMATE otherwise.

Storage vs decode reads:
  storage_params   = n_layers*per_layer + embed + (lm_head unless tied)
  decode_params    = n_layers*per_layer + lm_head  (read EVERY token; == embed tensor
                     when tied). Embedding matrix is NOT streamed at decode.
KV math (per new token, all layers): 2 (K,V) * n_kv_heads * head_dim * kv_elem_bytes
  read-back at decode with total ctx C = that * C  (linear in C).
"""
from dataclasses import dataclass
from typing import Dict


@dataclass(frozen=True)
class ModelSpec:
    name: str                     # CLI key
    display: str                  # human-readable
    n_layers: int
    n_heads: int                  # query heads
    n_kv_heads: int
    head_dim: int
    hidden: int
    ffn: int
    vocab: int
    tied_embeddings: bool
    total_params_hf: float        # from HF model card, sanity-check only (params)
    config_confidence: str        # source/confidence note (est for all: from memory)

    def per_layer_attn_params(self) -> int:
        qhd = self.n_heads * self.head_dim
        kvhd = self.n_kv_heads * self.head_dim
        return self.hidden * qhd + 2 * self.hidden * kvhd + qhd * self.hidden  # q,k,v,o

    def per_layer_mlp_params(self) -> int:
        return 3 * self.hidden * self.ffn  # gate + up + down (gated SwiGLU)

    def decoder_params(self) -> int:
        return self.n_layers * (self.per_layer_attn_params() + self.per_layer_mlp_params())

    def embed_params(self) -> int:
        return self.vocab * self.hidden

    def lm_head_params(self) -> int:
        return self.vocab * self.hidden

    def storage_params(self) -> int:
        lm = 0 if self.tied_embeddings else self.lm_head_params()
        return self.decoder_params() + self.embed_params() + lm

    def decode_params(self) -> int:
        """Params whose bytes are streamed every decode token (always includes lm_head)."""
        return self.decoder_params() + self.lm_head_params()

    def weight_bytes_per_token(self, bytes_per_weight: float) -> float:
        return self.decode_params() * bytes_per_weight

    def weight_bytes_storage(self, bytes_per_weight: float) -> float:
        return self.storage_params() * bytes_per_weight

    def kv_bytes_per_token(self, kv_elem_bytes: float = 2.0) -> float:
        """KV bytes written per new token across ALL layers: 2*KV*hd*L*b (f16 default)."""
        return 2 * self.n_kv_heads * self.head_dim * self.n_layers * kv_elem_bytes

    def kv_cache_bytes(self, ctx: int, kv_elem_bytes: float = 2.0) -> float:
        """Total KV cache at context ctx: linear in ctx by construction."""
        return self.kv_bytes_per_token(kv_elem_bytes) * ctx

    def flops_per_token_decode(self, ctx: int) -> float:
        """Decode flops/token: 2*decode_params + attention OVERHEAD (QK^T + A*V),
        linear in ctx: 4*L*n_heads*head_dim*ctx (2 passes * 2 flops * ctx * H * hd)."""
        return 2.0 * self.decode_params() + 4.0 * self.n_layers * self.n_heads * self.head_dim * ctx

    def flops_per_token_prefill(self, tokens: int) -> float:
        """Prefill flops/token (average over chunk, causal): dense 2*decode_params +
        4*avg_ctx*H*hd with avg_ctx=(tokens-1)/2 (per-token read of growing KV)."""
        attn = 4.0 * ((tokens - 1) / 2.0) * self.n_heads * self.head_dim
        return 2.0 * self.decode_params() + self.n_layers * attn


MODELS: Dict[str, ModelSpec] = {
    # qwen2.5-1.5b-instruct: 28L, hidden 1536, 12Q/2KV, hd128, ffn 8960, vocab 151936,
    # tied embeddings. ~1.54B. SOURCE: HF config.json. CONFIDENCE: HIGH (est).
    "qwen2.5-1.5b-instruct": ModelSpec(
        "qwen2.5-1.5b-instruct", "Qwen2.5-1.5B-Instruct", 28, 12, 2, 128,
        1536, 8960, 151936, True, 1.54e9, "HF config.json (est med-high conf)"),
    # qwen3-4b: 36L, hidden 2560, 32Q/8KV (GQA 4:1), hd128, ffn 9728, vocab 151936,
    # tied embeddings. ~4.02B. SOURCE: HF config.json. CONFIDENCE: HIGH (est).
    "qwen3-4b": ModelSpec(
        "qwen3-4b", "Qwen3-4B", 36, 32, 8, 128,
        2560, 9728, 151936, True, 4.02e9, "HF config.json (est med-high conf)"),
    # qwen2.5-7b-instruct: 28L, hidden 3584, 28Q/4KV, hd128, ffn 18944, vocab 152064,
    # untied. ~7.62B. CONFIDENCE: HIGH (est).
    "qwen2.5-7b-instruct": ModelSpec(
        "qwen2.5-7b-instruct", "Qwen2.5-7B-Instruct", 28, 28, 4, 128,
        3584, 18944, 152064, False, 7.62e9, "HF config.json (est med-high conf)"),
    # llama-3.1-8b-instruct: 32L, hidden 4096, 32Q/8KV, hd128, ffn 14336, vocab 128256,
    # untied. ~8.03B (rope-scaling 128k). CONFIDENCE: HIGH (est).
    "llama-3.1-8b-instruct": ModelSpec(
        "llama-3.1-8b-instruct", "Llama-3.1-8B-Instruct", 32, 32, 8, 128,
        4096, 14336, 128256, False, 8.03e9, "HF config.json (est med-high conf)"),
    # phi-3-mini-3.8b (llama arch): 32L, hidden 3072, 32Q/32KV (MHA), hd96, ffn 8192,
    # vocab 32064, untied. ~3.82B. CONFIDENCE: MED-HIGH (est; hd=96 less standard).
    "phi-3-mini": ModelSpec(
        "phi-3-mini", "Phi-3-mini-3.8B", 32, 32, 32, 96,
        3072, 8192, 32064, False, 3.82e9, "HF config.json (est med conf)"),
}


def get_model(key: str) -> ModelSpec:
    if key not in MODELS:
        raise KeyError(f"unknown model '{key}'; known: {sorted(MODELS)}")
    return MODELS[key]


# ---------------------------------------------------------------------------
# Quant class -> bytes per weight.
# EXACT by construction (gguf block layout, 32-wide legacy blocks):
#   Q4_0: block=32 w: 16 B nibbles + 2 B fp16 scale     = 18 B -> 0.5625 B/w (4.50 bpw)
#   Q8_0: block=32 w: 32 B int8 + 2 B fp16 scale        = 34 B -> 1.0625 B/w (8.50 bpw)
# EXACT (gguf k-quant super-blocks, 256-wide):
#   Q6_K: 210 B / 256 w = 0.8203125 B/w (6.5625 bpw)
# ESTIMATE ( mixture/blend, file-size derived; RECALIBRATE as file_GB / n_params ):
#   Q4_K_M: ~0.570 B/w baseline parameter (well-known ballpark ~4.56 bpw payload;
#           measured GGUFs land ~0.58-0.62 B/w incl. f16 embed/output tensors + metadata)
#   Q5_K_M: ~0.700 B/w (Q5_K super-block 176 B/256 = 5.50 bpw + Q6_K output mix)
# ---------------------------------------------------------------------------
QUANTS: Dict[str, float] = {
    "q4km": 0.570,        # EST; recalibrate from actual GGUF: file_bytes / n_params
    "q4_0": 18.0 / 32.0,  # exact 0.5625
    "q5km": 0.700,        # EST; recalibrate from actual GGUF
    "q6k": 210.0 / 256.0, # exact 0.8203125
    "q8_0": 34.0 / 32.0,  # exact 1.0625
    "fp16": 2.0,          # exact
}

QUANT_ALIASES = {
    "q4km": "q4km", "q4_k_m": "q4km", "Q4_K_M": "q4km",
    "q4_0": "q4_0", "q4_0": "q4_0",
    "q5km": "q5km", "q5_k_m": "q5km", "Q5_K_M": "q5km",
    "q6k": "q6k", "q6_k": "q6k", "Q6_K": "q6k",
    "q8_0": "q8_0", "q8_0": "q8_0", "Q8_0": "q8_0",
    "fp16": "fp16", "f16": "fp16",
}

# KV cache element bytes (per K or V element). Default fp16 (llama.cpp default, exact).
# RECALIBRATE if the engine stores q8_0/q4 KV (34/32=1.0625 or 18/32=0.5625 B/elem).
KV_ELEM_BYTES_DEFAULT = 2.0  # fp16


def bytes_per_weight(quant_key: str) -> float:
    q = QUANT_ALIASES.get(quant_key, quant_key)
    if q not in QUANTS:
        raise KeyError(f"unknown quant '{quant_key}'; known: {sorted(QUANTS)}")
    return QUANTS[q]


def canonical_quant(quant_key: str) -> str:
    q = QUANT_ALIASES.get(quant_key)
    if q is None:
        raise KeyError(f"unknown quant '{quant_key}'; known: {sorted(QUANTS)}")
    return q
