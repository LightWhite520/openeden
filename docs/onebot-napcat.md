# NapCat OneBot v11

OpenEden accepts NapCat through a OneBot v11 reverse WebSocket. NapCat opens the
connection; OpenEden does not log in to QQ and does not need Mirai.

## OpenEden configuration

Set these environment variables before starting the server:

```text
OPENEDEN_ONEBOT_ENABLED=true
OPENEDEN_ONEBOT_PATH=/onebot/v11
OPENEDEN_ONEBOT_ACCESS_TOKEN=replace-with-a-random-token
OPENEDEN_ONEBOT_BOT_SELF_ID=123456789
OPENEDEN_ONEBOT_GROUP_POLICY=MENTION_ONLY
OPENEDEN_OWNER_PLATFORM=QQ
OPENEDEN_OWNER_USER_ID=987654321
```

The default group policy is `MENTION_ONLY`. The available values are:

- `MENTION_ONLY`: reply in groups only when the bot is mentioned.
- `ALL`: process every text message in enabled groups.
- `DISABLED`: ignore group messages and keep private messages enabled.

The reverse WebSocket endpoint is:

```text
ws://127.0.0.1:8080/onebot/v11
```

Use the server's reachable address instead of `127.0.0.1` when NapCat runs in
another container or on another machine. Put TLS in front of the server and use
`wss://` when the connection crosses an untrusted network.

## NapCat configuration

In NapCat's OneBot v11 reverse WebSocket settings, configure:

- URL: the OpenEden endpoint above
- Access token: the same value as `OPENEDEN_ONEBOT_ACCESS_TOKEN`
- Reconnect: enabled

NapCat sends its bot ID in the `X-Self-ID` header. OpenEden accepts the
connection only when that value exactly matches `OPENEDEN_ONEBOT_BOT_SELF_ID`.

After connecting, NapCat sends message events to OpenEden. OpenEden returns
`send_private_msg` or `send_group_msg` actions over the same connection and
correlates the action response by `echo`.

## Session and heartbeat behavior

Private messages use `QQ:<user_id>` as their runtime session. Group messages
use `QQ:<group_id>`, so users in one group share one runtime state while the
sender ID remains message metadata.

Heartbeat output is sent only to the configured `QQ` owner as a private message.
If the reverse WebSocket is disconnected, heartbeat output is dropped and is not
replayed after reconnect.

One OpenEden server instance owns one configured QQ bot and one runtime kernel.
Run separate server instances with separate databases when independent QQ bots
are required.
