# Pod One — integration status and device test plan

## Branch safety

- Development work is on `feature/frostfrontend-menu`. The previously working local Android build and `develop` are left unchanged.
- Keep the user's own USA ROM, ROM-generated source (`RecompiledFuncs/`, `rsp/`) and built APK out of the public repo.
- The original sp00nznet and dguillot-gh MIT licensing and credits remain in place.

## Implemented on this branch (not yet validated on Odin)

### Launcher

- Replaced the basic two-button start screen layout with a gamepad-focusable **Pod One** launch menu.
- Play, Choose ROM, Settings, Save Management, Updates, Diagnostics and Quit.
- ROM selection, byte-order conversion, SHA-1 verification and the separated `:game` process are preserved.
- Local optional game artwork: `android/app/src/main/res/drawable-nodpi/pod_one_launcher_art.jpg`. The app uses its original vector art if absent. **Do not commit copyrighted cover artwork.**
- Approved icon can be installed locally as `android/app/src/main/res/drawable-nodpi/ic_launcher_foreground.png`. Android adaptive and legacy icon resources already refer to that name.
- Left menu uses dark blue with gold focus and keeps actual gameplay art outside the Android source distribution.

### In-game FrostFrontend

- Replaces `RacerMenu`; old menu implementation removed.
- Sections, in order: Graphics, Sound, Controls, Touch Settings, Save Management, Updates.
- Same settings values saved in the existing `racer` preferences, with one-time migration of old 21:9 index 2 to index 4.
- Gamepad Select/Menu opens settings. The original Start button is reserved for Racer.
- Gamepad inputs are suppressed during the settings overlay to avoid accidentally controlling the vehicle while navigating.
- Important: the original game's race simulation is **not yet paused** while FrostFrontend is open. Pause with Start first in the current test build.

### Graphics / RT64

- Original 4:3, Expand 16:9, Expand 16:10, Expand 3:2, Expand 21:9. RT64 Manual aspect target is used, not image stretching.
- 1x, 2x, 3x, Fit rendering; Sharp/Smooth scaling.
- MSAA Off/2x/4x; downsampling Off/2x/3x; output refresh Original/Display/Manual 60.
- Android Window Mode is fullscreen only; the menu shows the actual supported mode instead of offering a nonworking windowed toggle.
- **Needs on-device validation:** toggling MSAA and downsampling may require renderer reinitialization or be unsupported by a particular Vulkan driver.
- **HUD placement is not fixed.** The original N64 HUD may remain centred around 4:3. We deliberately did not patch arbitrary display-list rectangles or ROM addresses without a capture; later test the lap/time/position/minimap/speed readouts at all five ratios.

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
6. Test all five aspect ratios, including 16:10 for the Odin 2 Portal, in menus and during a race. Record HUD defects.
7. Try MSAA Off/2x/4x, downsampling Off/2x/3x individually. Restore Off if driver fails.
8. Compare audio delay before/after; check engine audio, announcer, long sessions and stereo speaker test.
9. Export a valid save to a document, then import it after exiting the game. Verify progress. Test delete with a backup.
10. Try release checking with and without internet. When there is no release, it should report that rather than claim an update exists.

## Known outstanding items

- Actual HUD anchoring across expanded ratios; requires GPU/display-list capture and N64 overlay grouping.
- Real pause/resume for settings overlay, rather than just suppressing inputs.
- ROM-independent public build/release verification: needs Windows/Android SDK/ROM-included local build, **not** a GitHub public artifact containing game code.
- The shared FrostFrontend library is still on its existing commit; this fork integrates it as a host. The six-tab standard, improvements from BlastDroid and further touch editor enhancements should be upstreamed to `Frostie77/FrostFrontend` separately without breaking earlier Android ports.
- Verify full launcher save path and gamepad handling on the Odin 2 Portal.

## Source references

- Original upstream: [dguillot-gh/star-wars-racer-recomp](https://github.com/dguillot-gh/star-wars-racer-recomp)
- Original recomp setup: [sp00nznet/racer](https://github.com/sp00nznet/racer)
- Shared frontend: [Frostie77/FrostFrontend](https://github.com/Frostie77/FrostFrontend)
- All original copyright notices and MIT attribution remain in `LICENSE`, `NOTICE.md` and `CREDITS.md`.
