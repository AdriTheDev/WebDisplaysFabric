# WebDisplays (Fabric)

A Fabric port of [WebDisplays](https://github.com/montoyo/WebDisplays), the Minecraft mod that
puts a real, interactive web browser on a block in the world. Build a screen, point it at a URL,
and click and type on it as you would on a monitor.

Rendering is provided by an embedded Chromium instance, so screens display real web pages —
including video — rather than static images.

## Requirements

| | |
|---|---|
| Minecraft | 26.1.2 |
| Mod loader | Fabric Loader 0.19.5+ |
| Dependencies | Fabric API |
| Java | 25 or newer |
| Browser backend | [MCEF Modern](https://modrinth.com/mod/mcef-modern) (optional, see below) |

### About the browser backend

The browser itself comes from MCEF (Minecraft Chromium Embedded Framework). The original
[CinemaMod MCEF](https://github.com/CinemaMod/mcef) has no build for 26.1 — its newest branch
targets 1.21.4 — so this port targets **MCEF Modern**, a maintained fork with a different API.

MCEF is an optional dependency, loaded reflectively. Without it the mod still loads and behaves
normally; screens simply stay blank instead of crashing the game. Note that MCEF Modern is
LGPL-2.1 licensed, while this mod is MIT.

## Blocks and items

| Item | Purpose |
|---|---|
| Web Screen | The display block. Screens tile into larger multi-block surfaces. |
| Screen Configurator | Right-click a screen to set its size, URL, and rotation. |
| Linking Tool | Right-click a screen, then a keyboard, to pair them. |
| Keyboard (Left / Right) | Type into a linked screen. |

All of them are in the **Web Displays** creative tab, or via command:

```
/screen <screen|configurator|linker|kb_left|kb_right> [count]
```

## Controls

| Input | Action |
|---|---|
| Left click | Click the page where you are looking |
| <kbd>Tab</kbd> | Toggle the on-screen cursor |
| <kbd>Shift</kbd> + scroll | Scroll the page |
| <kbd>Ctrl</kbd> + scroll | Zoom the page in / out |
| <kbd>F6</kbd> | Toggle screen rendering (useful for debugging) |
| Right-click a linked keyboard | Enter typing mode (<kbd>Esc</kbd> to leave) |

> **A screen's URL is stored in the world and visible to everyone.** Typing on a keyboard block
> stays on your own client and is never sent anywhere, but the address you set in the Screen
> Configurator is saved to the world and shown to any player who opens it.

## Multiplayer

Works on a dedicated server. Install the mod on the server and on every client; the server does
**not** need MCEF (it is client-only), but each player does, or their screens stay blank.

Screens are shared sessions. The server owns the screen's size, rotation, URL and volume, and it
also relays **clicks, scrolling and typing** to everyone watching the block. Click a link and the
page turns for all of you; type in a search box and everyone sees the letters appear.

Input is applied only when it comes back from the server, sender included, so every browser
receives the same events in the same order rather than the person interacting running ahead. Mouse
*movement* stays local, so you each keep your own cursor and hover — a click carries its own
coordinates and lands in the right place on every client regardless of where anyone is pointing.

The one thing this cannot make identical is anything the page decides for itself. Each client still
runs its own Chromium with its own cookies and logins, so per-account content differs, and a video
plays from each viewer's own buffer — expect playback to drift by a second or two rather than being
frame-locked.

Worth knowing before putting this on a public server:

- **There is no permission check.** A screen records who created it, but nothing enforces it — any
  player can retarget, resize or mute any screen.
- **There is no URL filter.** `isSiteBlacklisted` always returns `false`, so any player can display
  anything to everyone in range.
- **`/screen` is available to all players** and hands out the mod's items.
- Each screen is a full Chromium instance **per player in range**, so a wall of screens is
  expensive on clients.

## Building

You need JDK 25. Minecraft 26.1 ships unobfuscated, so no mappings are required.

```bash
./gradlew build
```

The mod jar is written to `build/libs/`. To try it in a development environment:

```bash
./gradlew runClient    # or: ./gradlew runServer
```

## Releases

Pushing a `v*` tag builds the mod and publishes a GitHub release with the jar attached — see
[`.github/workflows/`](.github/workflows). Every push and pull request is also built by CI.

```bash
git tag v1.3.0 && git push origin v1.3.0
```

The tag version and `mod_version` in `gradle.properties` should match; the release workflow fails
the build if they disagree.

## Project status

This is a port of a mod whose upstream targets much older Minecraft versions, so some of the
original feature set is not implemented here:

- **Working:** screens and multi-block surfaces, per-face URL / size / rotation, mouse and
  keyboard input, keyboard linking, block drops, the creative tab and the `/screen` command.
- **Not implemented:** the `mod://` URL scheme and its bundled pages, the URL blacklist
  (`isSiteBlacklisted` always returns `false`), per-screen ownership enforcement, redstone
  peripherals, and the other blocks from the upstream Forge mod (battery cells, minepad, remote
  controls, and so on). No crafting recipes are defined — use the creative tab or `/screen`.
- **Needs testing:** the in-world browser rendering path has been verified to compile and to
  degrade cleanly when MCEF is absent, but has not been exercised against a live MCEF Modern
  install. Treat it as unproven until you have run it.

## License

MIT — see [LICENSE](LICENSE). WebDisplays was originally created by
[montoyo](https://github.com/montoyo/WebDisplays).
