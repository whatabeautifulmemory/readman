<p align="center"><img src="assets/icon.png" width="96" alt=""></p>
<h1 align="center">Readman</h1>
<p align="center">Business-card photos → contacts, read by the vision LLM of your choice.</p>
<p align="center"><a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22io.github.whatabeautifulmemory.readman%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2Fwhatabeautifulmemory%2Freadman%22%2C%22author%22%3A%22whatabeautifulmemory%22%2C%22name%22%3A%22Readman%22%7D"><img src="assets/badge_obtainium.png" alt="Get it on Obtainium" height="48"></a></p>

Shoot cards one after another (or pick them from the gallery), delete the bad shots, tap **Analyze** — then review each result and save the batch to your contacts. No server, no account: photos go straight from your phone to the API you configure.

## Why (English)

Some business-card apps upload the cards a user received — other people's personal data — to their own servers in the name of "managing" them for the user. The person who handed over the card generally did so to be contacted; they never agreed to have their personal data posted to such a service. Some services even email that person to nudge them into using the app.

Readman exists to respect the people who gave you their card. A card goes from your phone straight to the model you chose and ends up only in your own address book; the app has no server and keeps no copy. With a local model (e.g. Ollama on your PC) it never leaves your network.

> Commercial AI services — anything other than a local model — are **not recommended**: the card image is sent to and processed on the vendor's servers, and whether your input is retained, used, or fed into model training varies from service to service. If you do use one, read its terms first and decide as the card's owner would want.

## Why (한국어)

일부 명함 관리 앱은 "이용자가 받은 타인의 명함을 관리해 준다"는 명목으로, 타인의 개인정보가 담긴 명함을 자사 서버에 저장합니다. 명함을 건넨 당사자는 일반적으로 자신에게 연락하게 하기 위한 용도로 명함을 제공한 것이지, 이러한 서비스에 자신의 개인정보가 게시되는 것에 대해서는 동의한 적이 없습니다. 심지어 일부 서비스는 그 사람에게 자사 앱 사용을 유도하는 이메일을 보내기도 합니다.

Readman은 나에게 명함을 건넨 사람의 개인정보를 존중하기 위해 만들었습니다. 명함은 내 폰에서 내가 고른 모델로 바로 전달되고 내 주소록에만 저장됩니다. 앱에는 서버가 없고 사본도 남기지 않습니다. 로컬 모델(내 PC의 Ollama 등)을 쓰면 데이터가 내 네트워크 밖으로 나가지 않습니다.

> 로컬 모델이 아닌 상용 AI 서비스의 이용은 **권장하지 않습니다.** 명함 이미지가 해당 사업자의 서버로 전송되어 처리되며, 서비스에 따라 입력 데이터의 보관·사용·모델 학습 활용 여부가 다를 수 있기 때문입니다. 굳이 쓴다면, 약관을 먼저 확인하고, 명함 주인의 입장에서 판단해 주세요.

## Features

- **Continuous capture** — the viewfinder stays open with a card-shaped frame; shoot card after card, only the framed area is kept. Or pick up to 50 photos from the gallery.
- **Review before spending** — nothing is sent to a model until you have deleted the bad shots and tapped **Analyze**.
- **Contact mapping** — per-field templates (`{name}`, `{organization}`, `{department}`, `{team}`, `{title}`, `{phone}`, `{mobile}`, `{fax}`, `{email}`, `{address}`, `{website}`, `{other}`), or let the model fill the contact fields itself. The extraction prompt is editable.
- English · 한국어 · 日本語, dark mode, Android 9+.

## Supported services

| Local / self-hosted | Cloud |
|---|---|
| **Ollama** (your PC) | Anthropic, OpenAI, Google Gemini, DeepSeek, Kimi (Moonshot), xAI, Mistral, Groq |
| **OpenCodex** gateway | OpenRouter, OpenCode Go, OpenCode Zen |
| **Any OpenAI-compatible endpoint** (LiteLLM, vLLM, LM Studio…) | |

Any model that accepts image input works; the model list is fetched live from the service.

## Setup

1. Settings → pick a service → paste its API key → **Fetch** → choose a vision-capable model.
2. Ollama on your PC: `OLLAMA_HOST=0.0.0.0 ollama serve`, then endpoint `http://<PC IP>:11434/v1` (plain HTTP on your own LAN).

## Privacy

Keys stay in app-private storage (excluded from backups). Captured photos live in the app cache until you delete the item. Contacts are written as device-local entries.

## Build

JDK 17 + Android SDK 35 → `./gradlew :app:assembleDebug`. Pushing a tag `vX.Y.Z` makes GitHub Actions publish the signed APK. Signing certificate SHA-256: `bc6bf2c051e4f14f03feb7d9b5a296e74a6ade468e0c141540668f6906f222e0`
