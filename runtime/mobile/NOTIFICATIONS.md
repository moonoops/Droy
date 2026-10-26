# Proactive notifications

`@droyagent/mobile/notifications` provides task notification channels. Enable it in General Settings → Droy proactive notifications. Local notices follow Android permissions and companion display preferences; App destinations require separate enablement and an allowed-app selection.

## Tools

- `droy_notification_channels` returns registered channels, availability and default recipients.
- `droy_notify` with `operation: send` accepts `noticeKey`, `text` and `destinations`; `title` and `attention` are optional.
- `operation: complete` settles an App notification using `noticeId`, `status` and post-send `evidenceCallId` for `sent_in_app`.

Built-in destinations are `system`, `pet` and `app`. Attention defaults to `quiet`. Reuse a stable `noticeKey` for the same matter in a turn; receipts prevent duplicate sends and do not automatically replay after restart.

An App result of `requires_action` means preparation only. The current agent must open the allowed app, send the requested content, observe the resulting conversation and complete the receipt. `sent_in_app` describes visible in-app evidence, not recipient delivery or reading. Unknown results remain unknown and are not automatically resent.

## Channel providers

Other plugins may register a channel through `ctx.droyNotifications.registerChannel({id, describe, send})` and dispose it with the plugin lifecycle. Providers must honor cancellation and return truthful per-channel outcomes. Native channel additions require an APK update.
