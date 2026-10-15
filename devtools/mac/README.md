# macOS drivers for the 1.21.1 port

- `rpc.sh <method> [json-params]` - call the MCPFabric bridge (reads the token from `run/config/mcpfabric.config.json`).
- `input.swift` - real OS mouse/keyboard events (`swiftc -O input.swift -o input`). Needs Accessibility permission for the
  terminal. `System Events` `click at` does NOT work for GLFW windows; CGEvent does.
  Usage: `input click X Y | rclick X Y | drag X1 Y1 X2 Y2 | scroll N | key CODE [shift,cmd,ctrl] | type TEXT` (screen points).
- Dev-only chat commands (registered only when `isDevelopmentEnvironment()`), sent with `rpc.sh chat.send`:
  `/flashback dev status | open <zip> | exit | seek <tick> | play | pause | export <noGui> <output>`.
  Each writes JSON to `run/flashback_dev_status.json` and logs `[dev] ...`.
- `control.setInput` is sticky: always send explicit `false` for keys you pressed (`{}` does not release them).

- `input chord <modifierKeyCode> <keyCode>` holds a real modifier key around a key press (macOS key codes: Cmd 55, Shift 56, Ctrl 59, Opt 58; Z=6, Y=16, P=35). Modifier *flags* on a plain `key` event are ignored by GLFW, so shortcuts like undo (Cmd+Z) only work through `chord`.
