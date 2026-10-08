# Conservative Jump Recovery for Voice Sync

Voice sync must distinguish intentional jump reading from accidental jumps caused by similar words later in the script. We decided to prioritize local continuity: if the current neighborhood can still match, later similar text must not steal the position; far-distance recovery is only allowed after repeated local failures and must meet stricter confidence requirements.

## Consequences

Intentional jump reading may recover one ASR packet later, but normal reading is less likely to jump to an unread later paragraph. This favors stable prompting over aggressive catch-up.
