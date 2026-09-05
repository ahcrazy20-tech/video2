# API availability audit — 5 September 2026

## Scope and verification method

This is a research audit for Video2's three workflows:

1. **Speech** — speech-to-text (STT) and text-to-speech (TTS)
2. **Subtitle review** — correcting ASR mistakes, repetitions, punctuation, and context with an LLM
3. **Translation** — subtitle translation

A provider is called **verified no-card** only when its own current documentation explicitly states that a payment card is not required, or when the plan documentation says that the free tier applies while no payment method is linked. "Free tier" by itself is *not* proof that a card is unnecessary. Limits and sign-up availability can be region/account dependent; check the linked dashboard before committing a production workflow.

Research date: **2026-09-05 UTC**. This is not an attempt to evade a provider's billing or identity checks.

---

## Recommended, verified no-card choices

| Provider | Workflows | Current free offer / limit | Verification and caveat | Fit for Video2 |
|---|---|---|---|---|
| **Google Gemini API / AI Studio** | Review, translation, STT, TTS | Selected Gemini models, Gemini 3.5 Transcribe/Live, and selected TTS models have a Free Tier. Actual RPM/TPM/RPD are project- and model-specific. | Official pricing labels the listed services “Free of charge”; paid tier requires billing/prepaid credits. Free tier is not offered in every country and free-tier prompts may be used to improve Google products. | **Best all-in-one primary**. Keep the live model discovery and check AI Studio Usage, rather than hard-code quotas. |
| **Mistral Studio Free mode** | Review, translation, STT (Voxtral Mini Transcribe 2), TTS (Voxtral TTS) | Free mode enables API keys without a credit card; included usage and rate limits are shown per organization in the dashboard. Public docs do not promise one universal numeric quota. | Documentation explicitly says Free mode needs no card. Verify that the desired Voxtral model is enabled for the particular account before shipping. | **Best new provider to add**: one OpenAI-style LLM API plus current audio endpoints. |
| **Deepgram** | STT, TTS, optional transcript analysis | **$200 one-time credit**, no expiration until spent; no card required. Provider says this is roughly 43,000 Nova transcription minutes, but treat that conversion as product/pricing dependent. | Official pricing page explicitly says no card required. Credit is a trial balance, not a recurring allowance. | **Strong replacement/addition** for Video2’s current STT and a good TTS fallback. The app already supports Nova-3 STT. |
| **Speechmatics Free** | Batch STT, realtime STT, Flow voice agent | **$100 credit grant** for a new Free plan. Current documented free limits: batch STT 10 hours/month; realtime STT 20 hours/month and 2 concurrent sessions. | Official plan documentation explicitly says Free has no payment card; card is required only when upgrading to Pro. Its credit grant and per-product limits should be checked in the portal. | **Already integrated for STT**. Update UI copy from 480 to current 10 hours/month / $100 grant. |
| **AssemblyAI** | Batch STT, subtitle analysis | **$50 one-time API credit**, no card required. | Official pricing page says $50 free credit and no card. Free accounts are throttled to one file concurrently; realtime streaming requires upgrade. | **Already integrated** and appropriate for long, one-file transcription. |
| **SambaNova Cloud Free Tier** | Review, translation | No payment method linked: for listed production LLMs **20 RPM, 20 RPD, 200,000 TPD**. | Official rate-limit documentation explicitly defines the Free Tier as accounts with no payment method. Current production IDs include `DeepSeek-V3.1`, `Meta-Llama-3.3-70B-Instruct`, and `gpt-oss-120b`; `DeepSeek-V3.2` is Preview. | **Already integrated**. It is a genuine no-card fallback but 20 requests/day means it is not enough for an entire long-video batch by itself. |
| **OpenRouter Free Models Router / `:free` variants** | Review, translation | Free variants and `openrouter/free`; current free-model limit is **20 RPM and 50 RPD** before buying at least $10 of credit, then 1,000 RPD. | Official documentation says Free plan access needs no card. Model availability and upstream capacity vary frequently. | **Already integrated**. Use the live catalog and prefer `openrouter/free` or a live `:free` model as the final fallback. |
| **Fish Audio S2.1-Pro Free** | TTS, voice cloning (with consent) | `s2.1-pro-free` costs $0 under **fair-use** limits; no published numerical quota / no latency or DPA guarantee. Phone verification is used to unlock free API credits. | Official docs explicitly identify it as a free development model. | **Worth adding as an evaluation-only TTS provider**, not an SLA-backed default. |
| **STT.ai** | STT | Website offer: **600 minutes/month**; its API FAQ says a free API tier has **100 minutes/month**. | Own pages confirm no-card web access, but one comparison page says API access is paid while several tool pages say API has 100 minutes. **Do not promise this in-app until a new API key has been tested in the dashboard.** | Existing integration can remain experimental; add a clear eligibility check/status label. |

### Important qualified provider: SiliconFlow

SiliconFlow’s docs still describe free models and a signup grant, but use of free models requires **identity verification**. Its availability, identity process, and model catalog are region-dependent. Treat it as a potentially useful existing fallback, **not** a universal “no-card/no-friction” recommendation.

---

## Good APIs that do *not* meet the no-card rule (or cannot currently be verified to meet it)

| Provider | Current finding | Decision for the no-card list |
|---|---|---|
| **Cerebras** | Current docs say new accounts get $5 trial credit **after adding a verified payment method**; access otherwise remains inactive. Credit expires after 30 days. | **Remove from no-card claims and automatic no-card recommendations.** Keep it as an optional card-verified trial/provider. |
| **Azure Speech / Translator** | F0 gives 5 STT hours/month, 500k TTS characters/month, and Translator F0 2M characters/month. But standard Azure Free Account sign-up requires a card. Azure for Students can be card-free after academic verification. | Keep as optional; label normal signup **card required**. |
| **Google Cloud Translation** | Has an allowance but Cloud Translation uses Cloud Billing. | Do not present as a no-card option. Use Gemini or self-hosted translation instead. |
| **Groq Orpheus TTS** | Groq migrated from PlayAI to Orpheus, but current Orpheus pricing is $22/M English or $40/M Saudi-Arabic characters. Groq’s docs publish free-tier rate-limit tables but do not make a clear no-card entitlement promise for this TTS model. | Update the broken PlayAI integration, but classify it as account/plan dependent—not a guaranteed free TTS API. |
| **ElevenLabs** | Free plan includes 10k credits/month and includes TTS/STT, but no-card entitlement was not established in the documentation checked. | Keep as optional TTS; do not rely on it as one of the strict verified no-card alternatives without confirming signup in the target country. |
| **DeepL API Free** | Official docs still support the `api-free.deepl.com` endpoint and the Free plan, but the official materials reviewed did not explicitly establish its current card requirement or current 500k figure. | Keep existing support; qualify the UI wording and verify during signup rather than promise no card. |
| **Cloudflare Workers AI** | Workers Free includes 10,000 Neurons/day. The catalog contains Whisper, MeloTTS and M2M100 translation. It is technically a very attractive single platform. | Add only after confirming the current account-signup/payment rule for the target user/region; official pricing reviewed confirms allowance but not the no-card condition. |

---

## New APIs / models to evaluate next

### 1. Mistral: one account for the entire pipeline

- **Review / translation:** Mistral Studio Free mode supports API keys without a card and exposes the actual allowance in the Limits page.
- **STT:** `voxtral-mini-latest` (Voxtral Mini Transcribe 2) has transcription and timestamp options.
- **TTS:** Voxtral TTS supports 9 languages, streaming and zero-shot cloning. Obtain explicit voice-owner consent; never enable arbitrary cloning by default.
- **Integration shape:** LLM is OpenAI-compatible. Audio endpoints need a separate multipart implementation. Start with review/translation, then add STT/TTS after testing a real free-mode key.

### 2. Fish Audio: genuine free developer TTS

- Model: `s2.1-pro-free`.
- Advantage: same model quality/language coverage as its paid S2.1-Pro model; no per-character price during fair use.
- Constraints: no published quota, no time-to-first-audio guarantee, and phone verification for free API credit. It should be a selectable beta provider with graceful fallback rather than the automatic default.

### 3. Cloudflare Workers AI: low-cost complete stack, but needs an account-ID/token architecture

Current models include:

- STT: `@cf/openai/whisper` / `@cf/openai/whisper-large-v3-turbo`
- TTS: `@cf/myshell-ai/melotts`
- Translation: `@cf/meta/m2m100-1.2b`

The Free plan’s 10,000 Neurons/day roughly maps (using the published current model mappings) to about 215 Whisper-Large-v3-Turbo audio minutes, about 536 MeloTTS output minutes, or much less direct M2M translation. It requires a Cloudflare API token and account ID. A consumer iOS app should not embed a broadly privileged Cloudflare token; use a narrow token or a small server-side proxy if this is adopted.

### 4. Groq’s new Arabic TTS migration

`playai-tts` and `playai-tts-arabic` were shut down on 2025-12-31. The replacements are:

- English: `canopylabs/orpheus-v1-english`
- Saudi Arabic: `canopylabs/orpheus-arabic-saudi`

The same `/openai/v1/audio/speech` endpoint remains, but requests are limited to **200 characters** and return WAV. Saudi Arabic voices: `abdullah`, `fahad`, `sultan`, `lulwa`, `noura`, `aisha`. This is a mandatory maintenance update for Video2’s current Groq TTS implementation.

---

## Self-hosted choices: no account, no card, no provider quota

These have infrastructure/compute cost, licenses, and maintenance burden, but no vendor API card requirement:

| Workflow | Options | Practical use |
|---|---|---|
| STT | Whisper / faster-whisper | Best offline/privacy fallback. Do batch transcription server-side or on supported local hardware. |
| TTS | Piper, Kokoro | Good offline narration fallback. Evaluate language/voice quality and model licenses before commercial use. |
| Translation | LibreTranslate / Argos Translate | Simple REST API when self-hosted; lower quality/language breadth than premium neural engines. |
| Grammar-only review | Self-hosted LanguageTool | Use for spelling/grammar. Do not send automated production traffic to LanguageTool’s public HTTP endpoint; its own docs prohibit that. |

A subtitle **semantic review** still needs a generative LLM; LanguageTool is a complement, not a substitute for ASR correction in context.

---

## Required Video2 maintenance updates found in this audit

### Must fix now

1. **Groq TTS is broken:** code still requests retired `playai-tts` and exposes retired PlayAI voice IDs. Migrate to the Orpheus model IDs, their real voices, the 200-character request cap, and Arabic-Saudi behavior.
2. **Cerebras is incorrectly presented as no-card / permanent free:** current documentation requires a verified payment method for the $5 / 30-day Free Trial. Remove “1M tokens/day without a card” from UI, catalog, README, and fallback marketing.
3. **SambaNova free tier is incorrectly described as a $5/30-day grant:** it now documents no-payment-method Free Tier limits of 20 RPM, 20 RPD and 200k TPD. Set stable `DeepSeek-V3.1` as default instead of Preview `DeepSeek-V3.2` for long-running jobs.
4. **Speechmatics UI copy is stale:** revise 480 minutes/month to the current Free plan wording: $100 grant, batch cap 10 hours/month (600 minutes), realtime 20 hours/month.
5. **Gemini has a newer current Flash model:** official pricing now lists `gemini-3.8-flash`; update the default/fallback preference from `gemini-3.7-flash` while retaining live discovery/recovery.

### Accuracy / product work to schedule

6. Do not say that *all* existing providers are card-free. DeepL, Azure, SiliconFlow, Groq and ElevenLabs need qualified per-provider wording.
7. Add **Mistral** first as the next provider; it covers review/translation and has current Voxtral STT/TTS endpoints under one no-card Free-mode account.
8. Add provider health/eligibility tests that identify `401`, `402`, `403`, and `429`, but do not claim remaining credit unless an API actually returns it.
9. Preserve a user-controlled “no-card only” filter. It should exclude Cerebras and normal Azure, and warn for providers whose signup rules cannot be confirmed automatically.
10. Preserve user consent and disclosure for all generated speech / voice-cloning functionality.

---

## Primary sources checked

- Gemini pricing, billing, rates and API errors: <https://ai.google.dev/gemini-api/docs/pricing>, <https://ai.google.dev/gemini-api/docs/billing>, <https://ai.google.dev/gemini-api/docs/rate-limits>
- Mistral Free mode and audio: <https://docs.mistral.ai/getting-started/quickstarts/studio/activate-and-generate-api-key>, <https://docs.mistral.ai/studio/audio/overview>
- Deepgram pricing: <https://deepgram.com/pricing>
- Speechmatics plans and limits: <https://docs.speechmatics.com/administration/plans>, <https://docs.speechmatics.com/speech-to-text/batch/limits>, <https://docs.speechmatics.com/speech-to-text/realtime/limits>
- AssemblyAI pricing: <https://www.assemblyai.com/pricing>
- SambaNova models and limits: <https://docs.sambanova.ai/docs/en/models/sambacloud-models>, <https://docs.sambanova.ai/docs/en/models/rate-limits>
- OpenRouter free routing and limits: <https://openrouter.ai/docs/guides/routing/routers/free-router>, <https://openrouter.ai/docs/api_reference/limits>
- Fish Audio free model: <https://docs.fish.audio/developer-guide/models-pricing/models-overview>
- Groq Orpheus and deprecation notice: <https://console.groq.com/docs/text-to-speech/orpheus>, <https://console.groq.com/docs/deprecations>
- Cerebras rate limits: <https://inference-docs.cerebras.ai/support/rate-limits>
- Cloudflare Workers AI pricing/models: <https://developers.cloudflare.com/workers-ai/platform/pricing/>, <https://developers.cloudflare.com/workers-ai/models/m2m100-1.2b/>
- LanguageTool public API policy: <https://dev.languagetool.org/public-http-api.html>
