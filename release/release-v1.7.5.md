# Deekseep 1.7.5

Deekseep 1.7.5 is the open-source release of Deekseep.

## Downloads

- `Open.apk` — Official Open edition release build with unobfuscated R8 dead-code stripping (8.8MB).
- `Open-debug.apk` — Official Open edition debug build (32MB).
- `Deekseep-1.7.5-source.tar.gz` — Complete open-source code archive for this release (5.0MB).
- `SHA256.txt` — SHA-256 checksums for all release assets.

Due to rampant unauthorized resale and malicious abuse, the Closed edition is no longer published or updated on GitHub. Legitimate users can join the official Telegram community (@Deekseepapp) to obtain Closed builds.

## Important Notice

Public open-source repository updates are suspended indefinitely following this release. Please refer to the Letter to Users displayed on startup and the Letter to Developers in the repository for detailed context.

## Highlights & What's New

### 1. Host Version Compatibility
- Full compatibility adaptation for Mainland China DeepSeek app v2.4.1 (versionCode 257).
- Retained backward compatibility paths for 2.3.6, 2.3.4, 2.3.0, and 2.2.x.

### 2. Chat & Message Management
- Multi-select chat batch deletion: One-tap deletion of selected sessions in sidebar.
- Local quota unlock: Removed local chat count modification limits.
- Thinking chain code copy: Added one-click copy button for reasoning and thinking code blocks.
- Anti-recall reliability: Fixed edge cases where recalled messages could disappear and preserved conversation context.

### 3. Security & Anti-Risk Bypass
- Risk control SDK bypass: Added runtime inline bypass for SMSDK / Shumei device risk-control detection routines.
- Anti-ban defensive protections: Implemented defensive heuristics to mitigate automatic bans.

### 4. UI Polish & Navigation
- Theme color synchronization: Dynamic host theme color palette synchronization with custom HEX overrides.
- Entry setting renamed: '使用原生入口' renamed to '使用旧版入口' for clarity.
- UI Navigation Manager: Integrated Activity and Compose route manager.

### 5. Diagnostic Tools & System Logs
- High-frequency unmasked raw hook trace logging with rotating logs.
- System diagnostic log and crash trace export (.zip).
- Application user data backup support.

### 6. Architecture & Extensions
- Java Plugin Framework v3 with dedicated hook catalogs and lifecycle management.
- Remote Feature Flags and gray-release configuration extensions.
- Built-in Termux runtime support for Agent workflows.
- Agent Model Context Protocol (MCP) server integration.
- Segmented multi-round prompt injection.

## SHA-256 Checksums
 
```
ff93ecc2e8e80d8efd45e3cbe98d4e56b5dd42b78b0c09e7159d95666f8a9d8f  Open.apk
35902a89c6bc6c4999e06c2fee7819d6e1317240c26edbf86b0934955bedd82b  Open-debug.apk
cf3dc7bef21f914dcf3c51d0732ed86da1302b53451d6876881f1741da694fb8  Deekseep-1.7.5-source.tar.gz
```

