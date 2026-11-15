# Pod One — integration status and device test plan

## Branch safety

- Development work is on `feature/frostfrontend-menu`. The previously working local Android build and `develop` are left unchanged.
- Keep the user's own USA ROM, ROM-generated source (`RecompiledFuncs/`, `rsp/`) and built APK out of the public repo.
- The original sp00nznet and dguillot-gh MIT licensing and credits remain in place.

## Implemented on this branch (not yet validated on Odin)

### Launcher

- The optional private art pack installs the approved 1672 × 941 launcher background with the ™ beside RACER removed. The background is a separate drawable at `drawable-nodpi/pod_one_launcher_background.png`. Public-source builds use the included vector backdrop.
- The launcher has five independently rendered buttons: ROM Selection / Play Game, Settings, Updates, Diagnostics and Quit. ROM Selection opens the Android picker. A verified import updates the button to Play Game. Press Y or tap Change ROM to replace an existing selection.
- ROM byte-order conversion, SHA-1 verification and the separate `:game` process are preserved. Android Back and controller B are handled separately; B remains available for in-game braking and settings navigation.
- The private asset pack includes the approved round icon with its gold rim and a 64 dp foreground badge. Gradle selects the local adaptive icon when its resource is installed.

### In-game FrostFrontend

- Replaces `RacerMenu`; old menu implementation removed. FrostFrontend uses the approved navy/gold layout with a proportional sidebar, aligned setting values and a selected-item description panel.
- Sections, in order: Graphics, Sound, Controls, Touch Settings, Save Management, Updates, Diagnostics, About. The latter two offer real own-app log sharing and port credits; no fake renderer/driver settings are added.
- Same settings values saved in the existing `racer` preferences, with one-time migration of old 21:9 index 2 to index 4.
- During a race, Start and the touch menu button send the original N64 Start input and open FrostFrontend after Racer has had time to draw its pause menu. Racer handles pausing and keeps its menu open after FrostFrontend closes. Select sends the original Start input only.
- The shared FrostFrontend test submodule now has the preview-inspired **left-hand sidebar** and **right settings panel**. Changes remain on the shared library feature branch until device testing.
- FrostFrontend captures player input while Racer continues to render. The D-pad and left stick move between the sidebar and options. A selects or cycles forward, and X cycles a setting backward.

### Graphics / RT64

- Original (4:3), Fixed (16:9), Fixed (16:10), Fixed (3:2), Fixed (21:9), and Expand to Screen. Expand to Screen fits the drawn portion of Racer’s VI image inside the full RT64 viewport.
- 1x, 2x, 3x, Fit rendering; Sharp/Smooth scaling.
- MSAA Off/2x/4x; downsampling Off/2x/3x; output refresh Original/Display/Manual 60.
- Android Window Mode is fullscreen only; the menu shows the actual supported mode instead of offering a nonworking windowed toggle.
- **Needs on-device validation:** toggling MSAA and downsampling may require renderer reinitialization or be unsupported by a particular Vulkan driver.
- Aspect and resolution changes replace RT64 render targets after both GPU queues finish using them.
- On Android, RT64 reserves framebuffer coverage for triangles crossing the camera plane and leaves their final clipping to the GPU. Race and menu visuals need verification on the Odin.
- The existing RT64 rectangle correction maintains HUD width. Per-element HUD anchoring and scenery culling need matching race captures across the aspect modes.

### Sound and input

- Sound → Audio Buffer switches between 512-frame Low Latency and 1024-frame Stable. The running game applies it on its next audio submission. The queue still limits accumulated latency to approximately 125 ms.
- Stereo Speaker Test is available in Diagnostics.
- Physical gamepad bindings saved via FrostButtonMapper and forwarded to SDL N64 button bits.
- Left stick steering and right stick C-camera input remain unchanged.
- D-pad bindings remain fixed. Mappable actions include Drift (Z) and four named C-button actions. The existing mapping IDs are preserved.
- Mapping conflicts are handled in FrostFrontend; bindings are reapplied on game focus.

### Touch and saves

- Edit Touch Layout is available from launcher settings and during a race. The navy/gold touch menu button defaults to the upper-left corner. A position the player saved is carried forward.
- The touch controls may be hidden without hiding the menu control.
- From the launcher, Settings → Save Management opens export, import with backup and confirmation, and delete.
- In-game Save Management directs the user to launcher settings after quitting the current game so an active save can close safely.
- Ensure the actual save path and imported format are validated against a real save on Odin.

### Graphics driver and diagnostics

- Stereo Speaker Test is in Diagnostics on both the launcher and settings screens.

- Graphics → Graphics Driver imports compatible Arm64 Adreno packages from a local ZIP with a root `meta.json` and its named Vulkan library. Driver changes take effect at the next game launch. Use System Driver is available in the same menu.
- **Share Startup Log** appears in Diagnostics, sharing Android device/build information plus up to 1,000 app-readable logcat lines. Modern Android restricts logcat to the calling app's available records. Review logs before sharing.

### Updates

- Queries the official `Frostie77/Pod-One` **latest GitHub release** API, with HTTP and timeout/error handling.
- No release is claimed to exist, and no ROM-derived APK auto-download is implemented.
- The launcher and FrostFrontend can both perform a release version check. The source project is meant to be rebuilt from the user's ROM.

## Test sequence

1. Fetch the feature branch and initialize all submodules. Confirm the original game still boots, plays, saves and has correct audio.
2. Install the personal artwork pack in `drawable-nodpi`, then build. Compare the icon and launcher to the approved designs.
3. Verify launcher and in-game settings with both D-pad and left stick. During a race use Start, wait several seconds, and check the race timer after resuming.
4. Rebind Accelerator/Brake/Z/R, restart the game, verify persistence. Confirm steering and camera sticks do not change.
5. Move the touch menu icon; hide touch buttons; reopen settings; restart and confirm icon position persisted.
6. Test all six aspect ratios, including 16:10 for the Odin 2 Portal, in menus and during a race. Record HUD defects.
7. Try MSAA Off/2x/4x, downsampling Off/2x/3x individually. Restore Off if driver fails.
8. Compare audio delay before/after; check engine audio, announcer, long sessions and stereo speaker test.
9. Export a valid save to a document, then import it after exiting the game. Verify progress. Test delete with a backup.
10. Try release checking with and without internet. When there is no release, it should report that rather than claim an update exists.

## Known outstanding items

- Actual HUD anchoring across expanded ratios; requires GPU/display-list capture and N64 overlay grouping.
- On-device verification of paused race timing and the selected Vulkan driver. A successful ZIP import confirms package validation. The game-start log records the actual Vulkan device and driver.
- ROM-independent public build/release verification: needs Windows/Android SDK/ROM-included local build, **not** a GitHub public artifact containing game code.
- Pod One currently pins a redesigned shared FrostFrontend **feature branch**. Shared `main` remains unchanged until it passes regression tests against the existing Android ports. Additional BlastDroid improvements should be reviewed for compatibility before merging.
- Verify full launcher save path and gamepad handling on the Odin 2 Portal.

## Source references

- Original upstream: [dguillot-gh/star-wars-racer-recomp](https://github.com/dguillot-gh/star-wars-racer-recomp)
- Original recomp setup: [sp00nznet/racer](https://github.com/sp00nznet/racer)
- Shared frontend: [Frostie77/FrostFrontend](https://github.com/Frostie77/FrostFrontend)
- All original copyright notices and MIT attribution remain in `LICENSE`, `NOTICE.md` and `CREDITS.md`.

## Visual reference validation (November 2026)

- The **only** launcher reference is the user-uploaded `Star Wars Racer_ Pod One Launcher.png`; it is installed as an entire screen with live hotspots, not used as a backdrop with a second Android menu over it.
- The **only** icon reference is the user's `Star Wars Racer Pod One Icon(1).png` (silver STAR WARS logo, gold RACER, silver POD ONE, two gold engine pods and magenta binder). No redesign or alternate icon generation.
- Android's round adaptive mask would crop the original full-bleed image; the private pack inserts the exact image into its safe inset, backed by blue. It also bumps the app development version so launcher icon caches can refresh. No app uninstall is necessary.
- Shared FrostFrontend's approved screenshot provides a left sidebar, right selectable rows with value arrows, explanation panel and controller prompts. It stays on `FrostFrontend/feature/pod-one-sidebar` and is pinned through Pod One's test submodule.
- These changes have **not yet passed an Odin 2 Portal visual regression test**. Verify the widescreen HUD and custom driver startup on hardware.

### Approved artwork lock — device fit test v3

- **Frozen launcher image:** `pod_one_launcher_reference.png`, 1672 × 941, copied pixel-for-pixel from the player's approved `Star Wars Racer_ Pod One Launcher.png`. SHA-256 of the local v3 resource: `c8261e4da23f55813240bd6aea1f34ae3c618496272a777b8d1e396483e44b99`.
- **Do not replace, repaint, regenerate, crop, stretch, recolor or modify this artwork for later menu changes.** Future menu work changes only layout, controls and input-hit areas. The local pack verifies the SHA before building.
- Launcher renderer now uses image's intrinsic size, a small overscan-safe inset and letterboxing instead of edge cropping, so the full reference image is always drawn on screen.
- **Circular Android icon:** approved original STAR WARS/RACER/POD ONE/podracer core is retained, but the surrounding gold border was converted from a squircle to a true circle as explicitly requested. The Android adaptive foreground remains inside the centre safe zone; the old v2 round-square icon is superseded.
- v3 artwork pack auto-detects JDK 17 after restarts. APK version 0.1.2-dev to encourage refresh of launcher cached icons.
- This is an **artwork-only** visual correction; UI layout or game mechanics should not be changed with it. Pending real Odin visual confirmation.
