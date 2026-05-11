---
name: Auto permission mode configuration
description: How to properly configure Claude Code for auto permission mode without prompts
type: feedback
originSessionId: 02c831ba-0cf5-4f26-a9e9-05f242991d56
---
To enable true auto permission mode (no prompts at all), the correct approach is:

1. Set `permissions.defaultMode: "auto"` in either global `~/.claude/settings.json` or project `.claude/settings.json`
2. The `permissions.allow` list alone (even with wildcards like `PowerShell(*)`) is NOT sufficient — the session's permission mode must be set to "auto"
3. Settings changes require a session restart to take effect — the running session cannot hot-reload permission mode
4. The `--permission-mode auto` CLI flag works for terminal sessions but requires proper stdin/TTY
5. For VS Code extension users, add `permissions.defaultMode` to settings files and restart the Claude panel

**Why:** The `permissions.allow` list reduces prompts for specific tool patterns, but only `defaultMode: "auto"` changes the underlying permission decision engine to auto-approve everything without asking.

**How to apply:** When a user wants no permission prompts, write `permissions.defaultMode: "auto"` to `.claude/settings.json` (project-level) or `~/.claude/settings.json` (global). Then tell the user to restart the Claude session.
