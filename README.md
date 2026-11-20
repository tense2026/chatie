# chatie

An Android chat client for the [BAZE](https://baze.co) API Gateway, written in
Kotlin and Jetpack Compose.

It exists to fix two things about using BAZE through its own PWA: the UI stutters
while answers stream, and it is hard to tell which project instructions or
memories actually reached the model.

## What BAZE's API does and does not give you

The gateway is a **stateless proxy**. One key and one base URL reach OpenAI,
Anthropic, Gemini, xAI and Perplexity models:

| | |
|---|---|
| Base URL | `https://factchat-cloud.mindlogic.ai/v1/gateway` |
| Auth | `Authorization: Bearer <key>` (or `x-api-key`) |
| Chat | `POST /chat/completions/` — OpenAI-compatible, SSE streaming |
| Models | `GET /models/?type=llm` |
| Credits | `GET /credits/` |
| Rate limit | 120 requests / 60s per member |

There is **no API for projects, conversations or memories** — those are features
of the BAZE app with nothing behind them you can call. So this app keeps all of
it on the device and sends only the `messages` array it assembled itself. That
constraint is what makes the scoping rules below possible.

## Context rules

Every request is built from five ordered layers, each one individually
switchable:

1. **기본 지시문** — app-wide, from Settings
2. **프로젝트 지시문** — only when the chat is in a project
3. **프로젝트 메모리** — only that project's
4. **전역 메모리** — opt-in; **off by default inside a project**
5. **대화 메모리** — notes attached to one conversation

The rules that make this predictable:

- A chat belongs to **zero or one** project. Nothing crosses between projects.
- The global-memory switch resolves through **conversation → project → app
  default**, and the UI names the level that decided rather than showing a bare
  toggle.
- A disabled memory is kept but not sent, so "what exists" and "what was sent"
  are separate questions.
- A per-chat system prompt replaces all five layers, and each skipped layer says
  so.
- Deleting a project deletes its instructions and memories; its chats survive as
  "프로젝트 없음".

**보내는 컨텍스트** (Context Inspector) renders exactly what the assembler
produced: every layer, included or not, the reason in plain language, the exact
text it contributed, and the final system prompt. If a line is not on that
screen, it was not in the request.

The rules live in `context/ContextRules.kt` as pure functions with no Android,
database or network dependency, and `app/src/test/.../ContextRulesTest.kt`
asserts all of the above.

## Why it does not stutter

- SSE deltas are appended to a `StringBuilder`; a **48 ms ticker is the only
  writer of Compose state**. An 80 tok/s model costs ~20 recompositions a
  second, not 80.
- Only the in-flight bubble recomposes — committed messages are separate
  immutable state, and `MessageBubble` takes only immutable values so Compose
  skips it.
- Markdown is parsed **once per finished message** inside `remember(content)`.
  The streaming bubble renders raw text: re-parsing half-written markdown on
  every flush is the usual reason a chat UI stutters.
- `LazyColumn(reverseLayout = true)` keeps the newest turn pinned to the bottom
  with no scroll animation per token.
- Reads are synchronous SQLite against indexed columns, so lists paint from disk
  on the first frame instead of showing a spinner.
- The assistant row is written before the first token and checkpointed every
  2 s, so being killed mid-answer leaves a partial answer, not a gap.

## Setup

1. Get a key: log in to BAZE on the web → **API Gateway** (bottom left) → **API
   키 생성**. It is shown once. Credits are charged to the account that created it.
2. Build and install:

   ```bash
   ./gradlew :app:installDebug
   ```

3. In the app: **설정 → BAZE API 키** → paste → **저장** → **연결 확인** (reads
   `/credits/` and shows the remaining balance).

The key is sealed with an AES-GCM key held in the Android keystore and stored
only as ciphertext. It is never written to the chat database, never logged, and
leaves the device only as the `Authorization` header to the gateway host. Cloud
backup and device transfer are disabled for every domain, and cleartext traffic
is off.

## Gateway failure modes handled

The documented ones that are easy to get wrong:

- A non-empty `User-Agent` is always sent — Cloudflare answers `403 error code:
  1010` without one.
- `401` is split by `detail.data.reason` (`revoked` / `disabled` /
  `admin_disabled` / `held`), so the message says whether you or an admin turned
  the key off.
- `402` with `detail.code = 6010` means the *reservation* exceeded the balance,
  not that it is empty — the gateway reserves the full `max_tokens` up front.
- `429` reports the `Retry-After` seconds.
- Reasoning models get a 16k output floor; a low cap returns an empty answer
  because thinking tokens consume the budget.
- Models that only accept `temperature: 1` are sent exactly that.

## Build

| | |
|---|---|
| Gradle | 9.9.2 (wrapper) |
| AGP | 9.5.1 (built-in Kotlin — no `kotlin-android` plugin) |
| Kotlin | 2.3.21 |
| compileSdk | 37.2 · minSdk 26 |
| Compose BOM | 2026.11.01 |

```bash
./gradlew :app:assembleDebug        # debug APK
./gradlew :app:testDebugUnitTest    # 22 unit tests, no device needed
./gradlew :app:assembleRelease      # R8-minified release APK
```

There is no Room and no KSP: the schema is hand-written SQL in `data/Db.kt`, so
the constraints that enforce memory scoping are readable in one place and builds
stay fast.

## Not implemented yet

File upload, image and video generation, web search, TTS/STT, Studio chatbot
calls (`/chatbots/{id}/chat/completions/`) and conversation sharing. The gateway
supports all of them; the app covers text chat first.
