# ADR 0001: Keep Activity state across configuration changes

- Status: accepted
- Date: 2026-11-01

## Context

The Android client keeps all of its state directly on `MainActivity`: more than
100 fields (bridge connection, WebSocket client, session list, conversation
items, approvals, executors, reconnect scheduling, caches) plus about 430
`fun MainActivity.*` extension functions spread over roughly 20 controller and
page files.

Before this decision, a rotation recreated the Activity, which tore down the
WebSocket client and dropped the in-flight turn UI until the user reconnected.
The two options considered were:

1. Move the state and the connection owner into a `ViewModel`.
2. Declare `android:configChanges` and keep the Activity instance.

## Decision

Use `android:configChanges="orientation|screenSize|keyboardHidden|density"`.

## Consequences

- Rotation, keyboard appearance, split-screen resize, and density changes no
  longer recreate the Activity, so the connection and conversation state stay
  intact. Compose relayouts from the new window constraints.
- The cost is that Activity state now outlives configuration changes. That is
  already how this code is written, and it is why the state lives on the
  Activity rather than in a `ViewModel` today.
- Other configuration changes (locale, font scale, dark mode, UI mode) still
  recreate the Activity. That is intentional: those change how resources and
  strings are resolved, and a recreation is the correct behaviour there.
- Moving to a `ViewModel` later remains an option, but it is not a small
  refactor: every `fun MainActivity.*` extension would need a new receiver, so
  it should be done as its own migration, not as a rotation fix.
- `onSaveInstanceState` is still not a state-restoration mechanism for the
  conversation. The bridge session snapshot stays the source of truth, and the
  session cache covers process death.

## Notes

`keyboardHidden` is included because the chat composer relies on window insets
and `adjustResize`; recreating the Activity when the keyboard appears would
drop the draft.