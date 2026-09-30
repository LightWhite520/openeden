# ChatGPT cache routing defect

The application sent a stable JSON `prompt_cache_key` but omitted the `session-id` HTTP header used by the ChatGPT subscription route. In all 17 previously saved cache-probe responses, the returned key differed from the requested key and was newly generated for each call. Stable local hashes therefore did not establish stable provider routing.

The upstream Codex implementation explicitly documents this distinction in [`responses_session_id`](https://github.com/openai/codex/blob/7219fd735bef2f9cfd0363fecdbbb212e3df5255/codex-rs/core/src/client.rs#L595). Its [header builder](https://github.com/openai/codex/blob/7219fd735bef2f9cfd0363fecdbbb212e3df5255/codex-rs/codex-api/src/requests/headers.rs#L5) sends `session-id`. The downloaded source was checked against that pinned revision. OpenEden continues using the public SIWC `/v1/responses` endpoint and its own authorized credentials.

## Repair

Subscription requests now send the existing opaque SHA-256 routing key in `session-id` as well as the supported JSON field. The header stays stable through appended history, dynamic feedback and JSON-field fallback, and changes with the history epoch. API-key requests are unaffected. No persona, history, Bio state, schema or model was removed or substituted.

The evaluation helper uses the same header when its request specifies a cache key. Its explicit `--without-session-affinity` option is only a diagnostic control. It does not change the application default.

## Bounded diagnostic evidence

Evidence is in `build/cache-session-affinity`. All calls used `gpt-6-luna` and completed successfully. The short control and treatment requests have identical JSON SHA-256 hashes; only the HTTP header differs.

| Request | Input tokens | Cached tokens | Reuse | Returned key |
|---|---:|---:|---:|---|
| No header, first identical short request | 8,989 | 4,864 | 54.11% | New UUID |
| No header, second identical short request | 8,989 | 7,936 | 88.29% | Another UUID |
| Header, first identical short request | 8,989 | 4,864 | 54.11% | Matches requested key |
| Header, second identical short request | 8,989 | 7,936 | 88.29% | Matches requested key |
| Header, appended short history | 9,009 | 7,936 | 88.09% | Matches requested key |
| Header, first existing long-history request | 12,423 | 4,864 | 39.15% | Matches requested key |
| Header, second identical long-history request | 12,423 | 12,032 | 96.85% | Matches requested key |

The same long-history payload had returned only 4,864 cached tokens in all three earlier probes without the header (`build/cache-runtime-probe-63424257/long-grouped-*-response.json`). The new key echo directly verifies the routing repair. The short controls also demonstrate that occasional large cache hits can occur without affinity; they must not be omitted to exaggerate the measured effect.

Three application-pipeline calls then continued a read-only copy of completed B-2's database in a new isolated database. They used the current installed application, actual DJL, the full persona/schema/history and three neutral messages. They all delivered successfully and advanced evolution 128 → 131:

| Application turn | Input tokens | Cached tokens | Reuse |
|---|---:|---:|---:|
| 1, cold route | 17,198 | 4,864 | 28.28% |
| 2 | 17,224 | 16,128 | 93.64% |
| 3 | 17,258 | 16,128 | 93.45% |

Artifacts: `build/ab-20260930-v2/runs/FIXED-1`, its seed manifest, and `build/cache-session-affinity/runtime-smoke.log`. This is a three-call routing smoke check, not the canceled 24-input golden scenario or a replacement A/B repetition.

## Limits and validation

The missing affinity header is repaired; a universal 95% floor is not established. Both completed historical B runs reported nonzero cached counts in 1,024-token steps (with the same 256-token offset), and the application still has a changing suffix containing new history and dynamic state. The local previews show the first changed content at the newly appended history and a roughly 2,800-character remaining suffix. This is evidence of where the prefix diverges, not an exact provider tokenizer measurement. The provider's precise cache-block policy and each component of the remaining uncached tokens have not been independently established.

The 29 subscription/Responses client regression tests passed, and `:server:installDist` succeeded. Tests inspect the actual serialized request and header through history growth, epoch changes and unsupported-body-field fallback. Log: `build/cache-session-affinity-check.log`.

The repeated A/B series and dependent watcher were stopped at the user's correction. Third-pair partial databases and failed/stopped manifests are preserved, with `abort-future-runs.json` preventing automatic later repetitions. No full benchmark was restarted after this diagnosis, and no cache acceptance threshold was lowered.
