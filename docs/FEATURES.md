# Source edition scope

The source edition contains the chat, account/privacy, appearance, debugging,
engineering, Agent, backup, notification, process-management, feature-flag,
custom greeting, custom assistant avatar, whale animation, and compatibility
implementations present in the public feature core.

It now also contains an independent, fully open-source implementation of the
Local API: an OpenAI- and Anthropic-compatible endpoint served from inside the
DeepSeek process, with per-device HTTPS, background keepalive, request
statistics, and optional public ingress. See [LOCAL_API.md](LOCAL_API.md) for
the endpoint reference and [LOCAL_API_GAP.md](LOCAL_API_GAP.md) for how it was
derived.

Two things from the packaged closed release are intentionally not reproduced:

- The runtime attestation / licensing components (`RuntimeProof*`), which exist
  to protect closed distribution and are meaningless for a source build.
- The protected distribution payload, which is encrypted and was not reverse
  engineered. The open Local API was written from the public OpenAI and
  Anthropic protocol specifications plus externally observable behaviour.
