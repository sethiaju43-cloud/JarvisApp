# JARVI AI

Android par hybrid personal assistant: **offline local AI** (phone par chalta hai) + **online AI** (free-tier friendly providers),
ek hi chat mein. Target: Redmi 13C 5G (6 GB RAM), par koi bhi arm64 Android 8+ phone.

> **Honest status:** Code poora likha gaya hai, par is delivery ke waqt Kotlin/Gradle compiler available nahi tha, isliye
> **build aur unit tests yahan chalaye nahi gaye**. GitHub Actions workflow pehle tests chalata hai, phir APK banata hai.
> Jab tak Actions green na ho aur APK phone par test na ho, ise "tested" mat samajhna. Phone par asli model test aap
> Models screen ke 🧪 Test button se kar sakte ho.

## Features (kya hai)

| Part | Kya bana | Kahan |
|---|---|---|
| Local offline AI | LiteRT-LM (Google) runtime, `.litertlm` quantized models, ek samay mein ek model, RAM check, idle unload, low-memory unload | `LocalLlm.kt`, `LocalModelCatalog.kt`, `ModelFit.kt`, `DeviceInfo.kt` |
| Model manager | Size/RAM/bhasha download se pehle, progress, resume, cancel, update check, remove, custom URL, internal storage, foreground service | `ModelDownloader.kt`, `ModelDownloadService.kt`, `ModelsActivity.kt` |
| Online providers | Gemini, Groq, OpenRouter, Cerebras, Mistral, Custom (OpenAI-compatible / apna backend). **Gemini zaroori nahi.** Sirf configured (key+model) providers hi available | `Providers.kt`, `ProvidersActivity.kt` |
| Model router | Task ke hisaab se order, 429/quota par agla model, 5xx par retry, key galat par skip, internet jaye to local, cooldown, active model naam | `RouterCore.kt`, `ModelRouter.kt`, `TaskClassifier.kt` |
| Reasoning | Mushkil sawaal par structured jawab (maqsad, jaankari, steps, vikalp+risk, best, nichod), chhupa chain-of-thought nahi | `Prompts.kt` |
| Self-correction | Calculation checker (app khud jaanchta hai), user correction handling, uncertainty rule, "code test nahi kiya" notice, optional double-check | `SelfCheck.kt`, `Brain.kt` |
| Fresh Mind | 🧠 button: summary, zaroori/faltu facts, asli sawaal, galti check, vikalp, behtar jawab, summary save option | `FreshMind.kt` |
| Memory | Encrypted, view/edit/delete, relevant yaadein hi prompt mein, sensitive baatein bina permission save nahi, history archive | `MemoryStore.kt`, `MemoryLogic.kt`, `ChatStore.kt`, `MemoryActivity.kt`, `HistoryActivity.kt` |
| Voice | Mic button se (kabhi chupke se nahi), partial text preview, stop speaking, offline/online voice label, permission flow | `VoiceInput.kt`, `VoiceHelper.kt`, `ChatActivity.kt` |
| Search | Gemini grounding + Tavily/Brave/Wikipedia, sources links, search fail par saaf notice, offline par local-knowledge notice | `SearchService.kt`, `Honesty` in `SelfCheck.kt` |
| Phone actions | App kholna, alarm/timer, call/SMS/WhatsApp (confirmation ke saath), settings pages, screen padhna/tap (Accessibility), photo/file attach | purane files + `Attachments.kt` |
| Security | Keys Keystore mein encrypted, kuch hardcode nahi, sirf HTTPS, errors mein key redact, custom backend option, untrusted file/search text ke liye guard | `ApiKeyStore.kt`, `Net.kt`, `network_security_config.xml` |

## Local models (verified Hugging Face litert-community pages se)

| Model | Size | Note |
|---|---|---|
| Qwen3 0.6B | ~586 MB | sabse halka |
| Qwen2.5 1.5B (int8) | ~1.6 GB | balanced |
| Qwen2.5 Coder 1.5B (int4) | ~1.1 GB | coding |
| Gemma 4 E2B | ~2.6 GB | best quality, 6 GB par tight |
| Gemma 4 E4B | ~3.65 GB | **experimental**, 6 GB par guarantee nahi. File ka naam pattern se liya, verify nahi hua |

RAM ke aankde andaaze hain. Hindi quality chhote models mein limited hai; Hinglish aksar behtar. Gated models (jaise Gemma 3) ke liye Models screen mein HuggingFace token daalo.

## Build

GitHub par push karo → Actions → **Build APK** → artifact `JARVI-AI-APK`.

Local: Android Studio (JDK 17, SDK 35) mein kholo, ya `gradle testDebugUnitTest assembleDebug`
(Gradle 8.7+; repo mein wrapper nahi hai, Android Studio ya `gradle wrapper` se bana lo).
LiteRT-LM version `app/build.gradle.kts` ke `litertLmVersion` mein hai (abhi 0.16.1; agar na mile to naya version daalo).

## Tests

JVM unit tests (`app/src/test`): calculation checker, think-stripper, sensitive filter, memory ranker, task classifier, context trimmer,
route planner, router core (retry/fallback/cooldown), API parsers, model-fit. Phone par chalne wale (instrumented) tests: **pending**.

## Pending / jo abhi nahi hai (imaandaari se)

- Build + tests abhi tak chalaye nahi gaye (upar dekho).
- Local model ka streaming/stop: streaming hai, par beech mein roka nahi ja sakta (runtime ka cancel API use nahi kiya).
- Local model se photo samajhna, PDF padhna, online reply streaming.
- Code ko phone par chalakar test karna (sandbox nahi hai; isliye app code ko "tested" nahi kehta).
- Wake-word / hamesha background listening: jaan-boojhkar nahi banaya (mic sirf button se).
- GPU backend experimental toggle hai, Mali-G57 par test nahi hua.
- Instrumented/UI tests, certificate pinning.

## Privacy

Chat, memory, keys encrypted (Android Keystore). Screen text AI ko bhejna Settings → Privacy se band kar sakte ho. Settings → "Saara local data mitao".
Docs: `docs/BACKEND_PROXY.md` (production mein keys server par).
