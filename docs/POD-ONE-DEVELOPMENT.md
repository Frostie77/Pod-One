# Pod One — integration status and device test plan

## Branch safety

- Development work is on `feature/frostfrontend-menu`. The previously working local Android build and `develop` are left unchanged.
- Keep the user's own USA ROM, ROM-generated source (`RecompiledFuncs/`, `rsp/`) and built APK out of the public repo.
- The original sp00nznet and dguillot-gh MIT licensing and credits remain in place.

## Implemented on this branch (not yet validated on Odin)

### Launcher

- The local test pack installs **exactly** the user-approved 1672×941 launcher reference as `drawable-nodpi/pod_one_launcher_reference.png`. LauncherActivity renders that image directly with functional touch/gamepad hotspots. The preview's logo, typography, button silhouettes, artwork and colors are not redrawn or replaced.
- Visual launcher buttons (as approved): Play Game, Settings, Save Management, Updates, Diagnostics and Quit. When there is no imported ROM, Play opens the ROM picker; when a ROM exists, pressing Y from the launcher permits re-importing it.
- ROM selection, byte-order conversion, SHA-1 verification and the separated `:game` process are preserved.
- The previous `pod_one_launcher_art.jpg` (original cover with the centered title) is superseded by `pod_one_launcher_reference.png` for local builds. The original public-source vector fallback remains when the private screenshot is absent. **Do not commit copyrighted game artwork.**
- Approved icon is the **unchanged** `Star Wars Racer Pod One Icon(1).png` the user supplied, scaled uniformly and inset within a 432px Android adaptive foreground's circular safe area. The private install pack provides `pod_one_icon_foreground.png`, `pod_one_icon_background.png` and the adaptive icon XML. Gradle selects `@mipmap/pod_one_adaptive` when installed; otherwise uses the public vector fallback. Old `pod_one_approved_icon.png` and `ic_launcher_foreground.png` test assets must be deleted.
- With the private image installed, the approved screen itself defines the launcher layout. Controller focus highlights the corresponding image hitbox; no platform Buttons are drawn over the artwork. The private pack has a built-in version-badge correction displaying the installed development version instead of the reference's illustrative release label.

### In-game FrostFrontend

- Replaces `RacerMenu`; old menu implementation removed. The right-hand FrostFrontend settings layout reproduces the approved dark navy/gold screenshot with the selected-item description card and controller footer.
- Sections, in order: Graphics, Sound, Controls, Touch Settings, Save Management, Updates, Diagnostics, About. The latter two offer real own-app log sharing and port credits; no fake renderer/driver settings are added.
- Same settings values saved in the existing `racer` preferences, with one-time migration of old 21:9 index 2 to index 4.
- Gamepad Select/Menu opens settings. The original Start button is reserved for Racer.
- The shared FrostFrontend test submodule now has the preview-inspired **left-hand sidebar** and **right settings panel**. Changes remain on the shared library feature branch until device testing.
- Gamepad inputs are suppressed during the settings overlay to avoid accidentally controlling the vehicle while navigating.
- Important: the original game's race simulation is **not yet paused** while FrostFrontend is open. Pause with Start first in the current test build.

### Graphics / RT64

- Original 4:3, Expand 16:9, Expand 16:10, Expand 3:2, Expand 21:9, and **Expand to Screen** (dynamic RT64 viewport, without stretching). RT64 Manual aspect target is used, not image stretching.
- 1x, 2x, 3x, Fit rendering; Sharp/Smooth scaling.
- MSAA Off/2x/4x; downsampling Off/2x/3x; output refresh Original/Display/Manual 60.
- Android Window Mode is fullscreen only; the menu shows the actual supported mode instead of offering a nonworking windowed toggle.
- **Needs on-device validation:** toggling MSAA and downsampling may require renderer reinitialization or be unsupported by a particular Vulkan driver.
- **HUD placement is not fixed.** The original N64 HUD may remain centred around 4:3. We deliberately did not patch arbitrary display-list rectangles or ROM addresses without a capture; later test the lap/time/position/minimap/speed readouts at all six ratios.

### Sound and input

- SDL audio buffer reduced from 1024 to 512 samples; queued-audio cap now scales with device frequency to approximately 125 ms. Test for improvement and crackle.
- FrostSpeakerTest is available in Sound.
- Physical gamepad bindings saved via FrostButtonMapper and forwarded to SDL N64 button bits.
- Left stick steering and right stick C-camera input remain unchanged.
- D-pad and Start remain fixed. Mappable actions: A, B, Z, R, C-Up/Down/Left/Right.
- Mapping conflicts are handled in FrostFrontend; bindings are reapplied on game focus.

### Touch and saves

- Existing touch pad is retained; the `≡` menu control can now be moved/resized in the touch editor and persists across launches.
- The touch controls may be hidden without hiding the menu control.
- Launcher save management: export, import with backup and confirmation, delete with confirmation.
- In-game Save Management directs the user back to launcher before importing/deleting to avoid corrupting an active save. No save states.
- Ensure the actual save path and imported format are validated against a real save on Odin.

### Graphics driver and diagnostics

- **Graphics Driver Selection** opens Android Developer Options for devices supporting per-app driver selection. This is a real OS shortcut, **not** custom Turnip driver loading. Custom drivers require a separately verified loader, such as AdrenoTools, before RT64 initializes.
- **Share Startup Log** appears in Graphics and launcher Diagnostics, sharing Android device/build information plus up to 1,000 app-readable logcat lines. Modern Android restricts logcat to the calling app's available records. Review logs before sharing.

### Updates

- Queries the official `Frostie77/Pod-One` **latest GitHub release** API, with HTTP and timeout/error handling.
- No release is claimed to exist, and no ROM-derived APK auto-download is implemented.
- The launcher and FrostFrontend can both perform a release version check. The source project is meant to be rebuilt from the user's ROM.

## Test sequence

1. Fetch the feature branch and initialize all submodules. Confirm the original game still boots, plays, saves and has correct audio.
2. Install the personal artwork pack in `drawable-nodpi`, then build. Compare the icon and launcher to the approved designs.
3. Verify pre-game settings and in-game six-tab FrostFrontend with touch, D-pad, A/B and shoulder tab navigation.
4. Rebind Accelerator/Brake/Z/R, restart the game, verify persistence. Confirm steering and camera sticks do not change.
5. Move the touch menu icon; hide touch buttons; reopen settings; restart and confirm icon position persisted.
6. Test all six aspect ratios, including 16:10 for the Odin 2 Portal, in menus and during a race. Record HUD defects.
7. Try MSAA Off/2x/4x, downsampling Off/2x/3x individually. Restore Off if driver fails.
8. Compare audio delay before/after; check engine audio, announcer, long sessions and stereo speaker test.
9. Export a valid save to a document, then import it after exiting the game. Verify progress. Test delete with a backup.
10. Try release checking with and without internet. When there is no release, it should report that rather than claim an update exists.

## Known outstanding items

- Actual HUD anchoring across expanded ratios; requires GPU/display-list capture and N64 overlay grouping.
- Real pause/resume for settings overlay, rather than just suppressing inputs.
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
- These changes have **not yet passed an Odin 2 Portal visual regression test**. No claims of fully corrected wide HUD or custom Turnip drivers are made.

### Approved artwork lock — device fit test v3

- **Frozen launcher image:** `pod_one_launcher_reference.png`, 1672 × 941, copied pixel-for-pixel from the player's approved `Star Wars Racer_ Pod One Launcher.png`. SHA-256 of the local v3 resource: `c8261e4da23f55813240bd6aea1f34ae3c618496272a777b8d1e396483e44b99`.
- **Do not replace, repaint, regenerate, crop, stretch, recolor or modify this artwork for later menu changes.** Future menu work changes only layout, controls and input-hit areas. The local pack verifies the SHA before building.
- Launcher renderer now uses image's intrinsic size, a small overscan-safe inset and letterboxing instead of edge cropping, so the full reference image is always drawn on screen.
- **Circular Android icon:** approved original STAR WARS/RACER/POD ONE/podracer core is retained, but the surrounding gold border was converted from a squircle to a true circle as explicitly requested. The Android adaptive foreground remains inside the centre safe zone; the old v2 round-square icon is superseded.
- v3 artwork pack auto-detects JDK 17 after restarts. APK version 0.1.2-dev to encourage refresh of launcher cached icons.
- This is an **artwork-only** visual correction; UI layout or game mechanics should not be changed with it. Pending real Odin visual confirmation.
