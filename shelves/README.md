# TV Shelves

A source fork of [CleanYourTV](https://github.com/Gathin23/cleanyourtv), based on commit `6ffa7fa5df7c38714cd89fd77fe636c53e759083`. It keeps the original clock, Outfit typography, app-color glow and glass focus styling, and adds media shelves and an editable dock. It is independent of the upstream project.

- Six pinned apps and an optional two recent apps in a horizontal dock.
- Continue Watching with source filters, recent/progress/app/title sorting and unstarted items hidden by default.
- Recommendations balanced across apps, with duplicate removal, filters and a daily mix.
- Stremio's published library channels, where available.
- An optional one-click Google Photos/Ambient Mode shortcut that preserves the idle screensaver selection.

Android TV 8 or newer is required. The original implementation was tested on Xiaomi Mi Box S (MIBOX4), Android TV 9, firmware PI.3933. The updated fork was installed alongside the existing launcher on that device. Fresh opt-in defaults, content loading, artwork, two recent slots, D-pad navigation, sorting persistence, explicit Home handover and saved-Home restoration were checked. The install-only flow now reasserts the previous Home because Android can clear its preferred Home when a second HOME app is installed. Photos recovery in this updated package still awaits a separately approved secure-settings grant; the original Photos implementation was already working on the device. Other TV firmware is not certified. This repository does not debloat devices, disable stock Home, add root access or flash firmware.

## Build and try

Install JDK 17, Python 3, Android SDK platform 34/build-tools 34.0.0, and platform-tools (`adb`). Set `JAVA_HOME` and `ANDROID_HOME`; accept the SDK licenses. Gradle's wrapper downloads Gradle/dependencies on first use.

```sh
./gradlew testReleaseUnitTest lintRelease assembleDebug
python3 -m unittest discover -s tests -v
python3 install.py --host <tv-address>
```

Windows: use `gradlew.bat` and `python`; `build-and-install.ps1` forwards the same installer arguments. The POSIX wrapper is `build-and-install.sh`. Installation builds a development/debug APK, explicitly preserves the recorded default Home even if Android clears it during installation, and starts `ShelfHomeActivity`. It never disables the stock launcher or grants optional permissions. Approve the device's ADB authorization prompt yourself.

To explicitly make TV Shelves Home after trying it:

```sh
python3 install.py --host <tv-address> --select-home
```

The installer saves the previous Home in ignored `.install-state/` before changing it and rolls back if selection verification fails. If the connection drops, run this recovery command when it returns:

```sh
python3 install.py --host <tv-address> --restore-home
```

Recovery only changes Home; it keeps applications, data and optional permissions intact. Keep the local recovery file. If the TV won't identify its current default Home, select one in TV settings first; the installer refuses to guess. The installer supports hostnames/IPv4 with optional ADB port, not IPv6 literals.

## Optional features and data

Long-press **Settings → Optional features**. Photos, recent apps and artwork downloads default to **Off**. No account APIs, telemetry or credential collection is added. Permission declarations alone do not grant usage or protected-settings access.

**TV listings:** allow the TV-listings permission when the launcher offers it. This reads metadata and app-published deep links from the local Android TV provider. Only Stremio, Prime Video, YouTube, Netflix, Spotify and JioHotstar are included. Availability depends on the installed app and region; a service can publish recommendations without Continue Watching. No separate Trakt/watchlist feed is fetched. Deep-link resumption is controlled by the source app. Stremio items without historical engagement dates stay after dated items; progress changes observed locally supply future recency. Observed progress metadata remains in private app storage.

**Artwork:** enabling downloads discloses the device's public IP and requested artwork identifiers to seven HTTPS hosts: `i.ytimg.com`, `images-eu.ssl-images-amazon.com`, `occ-0-1492-3663.1.nflxso.net`, `i.scdn.co`, `img1.hotstarext.com`, `images.metahub.space`, `live.metahub.space`. Other origins and redirects are blocked. This narrow list can mean missing artwork outside the tested region. The loader uses two workers, an 8 MB memory cache, a 40 MB disk cache and a 3 MB download cap. Disabling prevents new requests and clears queued work/memory; an in-flight request can finish. Cached artwork remains until cleared through Android's app storage settings.

**Recent apps:** enabling this feature reads seven days of local foreground usage to select two non-pinned apps. Enable Usage access in TV settings if offered; otherwise the optional ADB command is:

```sh
adb -s <tv-address>:5555 shell cmd appops set io.github.tvhomeshelves.launcher GET_USAGE_STATS allow
```

To revoke it, use the same command with `default` instead of `allow`. Turning the feature off stops querying usage history. It does not upload it.

**Photos:** Android TV 8–11 only; actually verified only on the original Android 9 device before these recovery changes. Google Backdrop and SystemUI's compatible screensaver shortcut must be installed. The Photos button stays hidden until enabled and supported. It needs this separately chosen grant:

```sh
adb -s <tv-address>:5555 shell pm grant io.github.tvhomeshelves.launcher android.permission.WRITE_SECURE_SETTINGS
```

This is broad, persistent access to protected system settings, not a permission limited to Photos. It is never granted by the installer. After granting, explicitly enable Photos in Optional features. To revoke, turn Photos off first, then run:

```sh
adb -s <tv-address>:5555 shell pm revoke io.github.tvhomeshelves.launcher android.permission.WRITE_SECURE_SETTINGS
```

The shortcut durably saves the idle selection, schedules an independent inexact fallback alarm, selects Google Backdrop and starts the system screensaver. It restores the original selection when Android broadcasts that the dream has started, without a two-second race. Delayed startup, launch failure, a new process, later Home entry and boot recovery are covered by the recovery design. Restoring the selection does not stop the already running Google slideshow.

Force-stop suppresses alarms and receivers; reopen Home to recover a pending selection. Revoking secure-settings access before recovery can prevent restoration: restore the permission and reopen Home, or choose the clock manually in TV settings. Fallback alarms can be delayed by Android. Startup-event and reboot recovery still need verification on real devices; no universal guarantee is made. A user-selected different screensaver is preserved rather than overwritten.

Personal photos are configured by Google, not this launcher: Google Home → device settings → Ambient mode → Google Photos → choose an album or Recent Highlights, if available. Without a selected source Google can show its art gallery. The launcher does not change idle timing or the selected Google album.

## Identity and release signing

The application ID is `io.github.tvhomeshelves.launcher`, separate from upstream `com.gathin.tvhome`, so the two can coexist. The Java namespace remains upstream's. The Home component is `io.github.tvhomeshelves.launcher/com.gathin.tvhome.ShelfHomeActivity`. The optional clock service is `io.github.tvhomeshelves.launcher/com.gathin.tvhome.ClockDream`; selecting it is a separate choice in TV settings. Installation does not change screensaver selection.

A debug APK uses the local development key. Keep that key for debug updates; builds from another computer may not install over it. Never commit keys or publish debug-signed builds as releases. Release builds are **unsigned** unless you supply ignored `signing.properties`:

```properties
storeFile=/absolute/path/outside/repository/release.jks
storePassword=your-local-password
keyAlias=release
keyPassword=your-local-password
```

Create and back up your own release keystore outside the repository, then run `./gradlew assembleRelease` or `python3 install.py --host <tv-address> --release`. The installer refuses release installation without that file. Use the same signing key for updates; switching from debug to release under the same package cannot update in place. Do not distribute the private key or passwords. An alternate application ID can be supplied via `--application-id` or `-PlauncherApplicationId`; inherited classic accessibility/helper integrations retain upstream assumptions and are outside this fork's supported flow.

## Sharing source

Commit only the clean export prepared by `export-source.py`, not a TV-maintenance workspace. The exporter uses a file allowlist, excludes personal notes/screenshots, build outputs, credentials and recovery files, and refuses known sensitive patterns. Review the actual staged files before any push. Included app resource images are upstream source assets, not TV screenshots.

Source is MIT licensed; retain [LICENSE](LICENSE) and upstream attribution. Outfit fonts remain under [SIL OFL](FONT-LICENSE-OFL.txt). Android dependencies retain their own licenses. No private device captures or prebuilt APKs are included.
