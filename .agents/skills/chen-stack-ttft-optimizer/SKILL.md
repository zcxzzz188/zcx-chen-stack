---
name: chen-stack-ttft-optimizer
description: Measure and reduce Chen Stack Spring AI customer-service first-token latency while preserving retrieval evidence, permissions, and complete SSE answers. Use for TTFT or latency work in the customer-service FAQ, RAG, Wiki, Hybrid, or navigation paths.
---

# Chen Stack TTFT Optimizer

Optimize the real customer-service request path from authenticated HTTP request to the first user-visible SSE text, then verify the complete answer and G6 behavior. Use the repository's existing Spring AI observations first; add only missing spans or timers.

## Workflow

1. Read the repository `AGENTS.md`, inspect `git status` and only the relevant diffs, and keep pre-existing edits intact. Never perform Git writes. Do not change database schema, redesign UI, rebuild Qdrant, restore a 200-token cap, or make the application depend on this Skill.
2. Confirm the active profile, model/provider, knowledge mode, and data writes before benchmarking. Prefer an isolated test identity and store. If the user explicitly authorizes a bounded test against the existing local G6 service, that authorization may be used even when its MySQL/Redis/Qdrant are shared: cap the run, use a dedicated test account, generate unique `chatId`/`requestId` values, snapshot the exact rows before and after, and remove only this run's synthetic chat-history rows after verifying their owner and IDs. Never delete or rewrite usage/quota/provider ledgers. Do not infer that a local service is isolated or send traffic to a remote production host. Real DeepSeek calls consume provider quota; require explicit authorization. If identity, local host, or cleanup scope cannot be verified, finish independent source analysis and offline checks and report the blocker.
3. Define fixed FAQ, RAG, Wiki, Hybrid, and page-navigation cases from non-sensitive, read-only questions. Warm the JVM and connections, then run a small, explicitly capped number of sequential samples with fresh conversation and request IDs. Record raw samples before any source change. Keep the run count, model, provider settings, data snapshot, and cache state in the report. If history is persisted, verify and clean only the exact synthetic session/message rows created by this run; retain truthful provider usage and quota records.
4. Build an end-to-end timeline for each request: HTTP arrival; authentication; intent/mode classification; embedding; Qdrant; RAG and Wiki lookup; each tool call; prompt assembly; DeepSeek request; first non-empty model answer text; first user-visible SSE正文; and full stream completion. Keep model/tool tokens separate from visible answer text. Read the call graph and trace to identify true serial dependencies; remove a provably unnecessary model round before considering concurrency. Do not parallelize work whose result is needed by a later stage.
5. Rank costs by measured contribution. Prefer removing redundant LLM work, especially in the three-call Hybrid and navigation paths, while retaining any call required for grounding, routing, tool execution, or answer validation. Change one attributable factor per iteration.
6. Re-run the exact cases against the same isolated data and real DeepSeek. Report raw runs, sample count, P50, P95, full completion time, model-call count, and provider-reported input/output tokens. Use provider usage or trace evidence; never infer calls or tokens from latency. If a field is unavailable, mark it unavailable and do not claim a complete comparison. With small samples, label P95 as a rough estimate.
7. Run the affected automated tests and the G6 regression checks required by the task. Compare retrieval relevance, citation identity, authorization, tool behavior, answer completeness, and terminal SSE status. If any correctness gate fails or the change has no repeatable latency/token benefit, undo only this task's isolated source edits after checking for concurrent changes; never use Git restore/reset.
8. Save the baseline, candidate, raw evidence, and concise comparison report under repository `.work/`. Keep all reusable Skill files and test helpers inside this Skill directory. Do not deploy unless the verified candidate requires deployment and the user has authorized the exact production change; deployment is not a substitute for acceptance.

## References and helper

- Read [references/measurement-workflow.md](references/measurement-workflow.md) for measurement, trace, and correctness details.
- Read [references/chen-stack-timing-map.md](references/chen-stack-timing-map.md) when tracing the current backend path or mapping Spring AI observations.
- Use `scripts/benchmark_sse.py` for sequential HTTP/SSE timing on a dedicated test account. It uses only Python's standard library, never prints credentials or request bodies, records generated synthetic IDs in `.work/` for exact cleanup, and writes evidence only under `.work/`.

The performance-profiler reference was reviewed for its measurement-first, hotspot-attribution, one-change, and re-measure loop. Its scripts were not copied or executed.
