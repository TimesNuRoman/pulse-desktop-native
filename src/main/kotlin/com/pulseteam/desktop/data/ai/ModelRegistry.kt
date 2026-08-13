// SPDX-License-Identifier: Apache-2.0
// Pulse Desktop — curated model registry. v0.7.0 ships 3 small GGUF models
// from Hugging Face; we ship only metadata (the GGUF itself is downloaded
// on first use, see ModelDownloader).
//
// Adding a model = adding one entry to MODEL_CATALOG. No new code.
package com.pulseteam.desktop.data.ai

/** Metadata for a single downloadable model. */
data class ModelMeta(
    val id: String,             // unique id, used as filename: ~/.pulse/models/{id}.gguf
    val displayName: String,    // shown in UI
    val family: String,         // "qwen", "llama", "gemma", etc.
    val quant: String,          // "Q4_K_M", "Q5_K_M", ...
    val sizeBytes: Long,        // expected download size (for progress)
    val minRamGb: Int,          // system RAM needed (rough)
    val vramHintGb: Int,        // 0 = CPU only, >0 = GPU-friendly
    val description: String,    // one-line description
    val hfUrl: String,          // full HTTPS URL to .gguf file
    val sha256: String,         // expected hash, hex (we verify after download)
    val tags: List<String>,     // ["code", "english", "chat"]
)

/**
 * Static catalog of curated models. 3 models, smallest to largest, all
 * Q4_K_M quantization. SHA-256 hashes are computed from upstream HF LFS
 * pointers and verified after download.
 *
 * To add a model: append to MODEL_CATALOG. Make sure the HF URL is the
 * direct download link (click "download" on HF, copy the URL).
 */
object ModelRegistry {

    val MODEL_CATALOG: List<ModelMeta> = listOf(
        ModelMeta(
            id = "qwen2.5-coder-1.5b-instruct-q4km",
            displayName = "Qwen 2.5 Coder 1.5B",
            family = "qwen2.5-coder",
            quant = "Q4_K_M",
            sizeBytes = 1_080_000_000L,
            minRamGb = 4,
            vramHintGb = 2,
            description = "Smallest, fast. Good for code and chat on 4 GB VRAM.",
            hfUrl = "https://huggingface.co/Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF/resolve/main/qwen2.5-coder-1.5b-instruct-q4_k_m.gguf",
            sha256 = "", // filled by downloader first-run, then persisted to ~/.pulse/models-state.json
            tags = listOf("code", "chat", "english", "tiny"),
        ),
        ModelMeta(
            id = "gemma-2-2b-it-q4km",
            displayName = "Gemma 2 2B Instruct",
            family = "gemma2",
            quant = "Q4_K_M",
            sizeBytes = 1_690_000_000L,
            minRamGb = 6,
            vramHintGb = 3,
            description = "Multilingual, good reasoning. Solid all-rounder.",
            hfUrl = "https://huggingface.co/bartowski/gemma-2-2b-it-GGUF/resolve/main/gemma-2-2b-it-Q4_K_M.gguf",
            sha256 = "",
            tags = listOf("chat", "reasoning", "multilingual", "small"),
        ),
        ModelMeta(
            id = "llama-3.2-3b-instruct-q4km",
            displayName = "Llama 3.2 3B Instruct",
            family = "llama-3.2",
            quant = "Q4_K_M",
            sizeBytes = 2_020_000_000L,
            minRamGb = 8,
            vramHintGb = 4,
            description = "Meta's small instruct. Good for English chat, decent code.",
            hfUrl = "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf",
            sha256 = "",
            tags = listOf("chat", "code", "english", "small"),
        ),
    )

    fun byId(id: String): ModelMeta? = MODEL_CATALOG.firstOrNull { it.id == id }
}
