# Pocket Agent

A bring-your-own-key AI chat and agent app for Android, styled like the Claude and Gemini apps.

## What it does

- **Any provider with an API key**: OpenRouter, OpenAI, Gemini, DeepSeek, Groq, or any OpenAI-compatible URL.
- **Switch models without opening settings**: tap the model name at the top. Recent models are listed, "Browse all" loads the provider's full list.
- **Thinking level**: Off / Low / Medium / High, in the same sheet.
- **Multiple chats**: open the menu (top left) for your chat list and "New chat".
- **Auto-switch**: if one provider hits a limit or runs out of credit, the next one is used.
- **Agent mode**: the AI reads and writes files in a private workspace. When it builds an app or website it zips the project, and you get "Save to phone" and "Share" buttons.
- **Upload files** with the paperclip (text, code, or zip files; zips are unpacked).
- **GitHub repos**: paste a repo link in the chat, or use Settings > Files. Private repos work with a GitHub token.
- **Skills**: import `SKILL.md` skills from a GitHub link (Settings > Skills). Type `/skill-name` in a message to load one.
- **Phone control** (off by default): the AI can open apps, set alarms and timers, tap, type and scroll. Needs the accessibility service (see below).
- **Claude Code mode**: send messages to the real Claude Code running in Termux. This is the way to use services like AgentRouter that only accept Claude Code. Setup is in Settings > Termux.
- **Usage tab**: tokens and cost per provider (exact for OpenRouter, estimated elsewhere).

## Get the APK from GitHub (no Android Studio needed)

1. Create a new GitHub repository (it can be private).
2. Upload everything in this folder, including the hidden `.github` folder. Easiest with git:
   `git init && git add . && git commit -m "init" && git branch -M main && git remote add origin <your repo url> && git push -u origin main`
3. Open the repo's **Actions** tab. The "Build APK" workflow starts by itself (about 5 to 8 minutes the first time).
4. When it finishes, open the run and download **PocketAgent-debug-apk** under Artifacts. Unzip it and install `PocketAgent-debug.apk` (allow "install unknown apps" when asked).

## Turning on phone control

1. In the chat, tap **+** and switch on **Phone control**.
2. Tap "Open settings", find **Pocket Agent phone control** and turn it on. Android shows a warning because the permission is powerful.
3. Ask for something ("set a timer for 10 minutes", "open YouTube and search for lofi").

Each request asks you to allow phone control first, and a floating **Stop AI** button stays on screen while it works. Only turn this on when you are the one typing the requests.

## Using a Gemini Pro subscription

The Gemini app subscription cannot be used by other apps. Get a separate API key at `aistudio.google.com/apikey` (there is a free tier), then in Settings > Providers tap "Gemini" and paste it.

## Notes

- Model IDs change often. Use the model picker to see what a provider offers right now.
- A low "Max reply tokens" value protects small credit balances. Raise it (and use thinking) for long answers.
- This is the debug build, signed with a debug key. That is fine for personal use.
