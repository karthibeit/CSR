# OneTamilMV — CloudStream Extension

A CloudStream 3 provider for the **1TamilMV** forum: Tamil, Telugu,
Malayalam, Kannada and Hindi movie / web-series releases.

Built from the official `recloudstream/TestPlugins` template.

## How it works — read this first

1TamilMV is **not** a streaming site. It is a XenForo forum:

- Every release is a forum **thread**.
- The **first post** of the thread holds the links, grouped by quality
  (360p / 480p / 720p / 1080p / 2160p).
- Most links are **magnet / .torrent** links. Some threads also include
  **direct** (.mp4 / .mkv / .m3u8) or **file-host** download links.
  There are no embedded streaming players to scrape.

What the provider does with that:

| Link type in the thread | What you get in CloudStream |
| --- | --- |
| Direct .mp4 / .mkv | Playable video link |
| .m3u8 | Playable stream |
| File-host page | Passed to CloudStream's built-in extractors; if none matches, surfaced as a download link |
| Magnet link | Surfaced as a `MAGNET` link — CloudStream's library currently marks magnet/torrent as *"No support at the moment"*, so use it with the download / external torrent flow, not in-app playback |
| .torrent attachment | Surfaced as a `TORRENT` link (same caveat as magnet) |

## The domain problem

1TamilMV domains rotate constantly (ISP blocks, parked domains).
On 2026-10-23, `1tamilmv.fi` was serving a registrar parking page and
several mirrors were unreachable, which is exactly why no maintained
1TamilMV extension existed before this one.

The provider handles it two ways:

1. `mainUrl` starts at `https://www.1tamilmv.cz` (last widely reported
   domain) and, if that fails or returns a parking page, it walks the
   fallback list in `fallbackDomains`
   (`OneTamilMVProvider.kt`) / `domains.json` until a live mirror
   answers, then sticks with that mirror.
2. When the site moves again: add the new domain at the **top** of both
   lists, bump `version` in `OneTamilMVProvider/build.gradle.kts`,
   and push — GitHub Actions rebuilds the plugin.

## Publish it to your GitHub

1. Create a new GitHub repo named `cloudstream-onetamilmv`.
2. Push this folder to the `master` (or `main`) branch.
3. In `repo.json`, replace `YOUR_USERNAME` with your GitHub username
   and commit.
4. Create an empty `builds` branch:
   `git checkout --orphan builds && git rm -rf . && git commit --allow-empty -m "builds" && git push origin builds`
   then switch back to `master`.
5. Push to `master` — the **Build** workflow (`.github/workflows/build.yml`)
   compiles the plugin and pushes `OneTamilMVProvider.cs3` +
   `plugins.json` to the `builds` branch.

## Install in CloudStream

1. Open CloudStream → Settings → Extensions → Add Repository.
2. Paste:
   `https://raw.githubusercontent.com/YOUR_USERNAME/cloudstream-onetamilmv/master/repo.json`
3. Install **1TamilMV** from the repository list, then select it as a
   provider on the home / search screens.

## Build locally (optional)

Requires JDK 17 and the Android SDK:

```bash
./gradlew OneTamilMVProvider:make makePluginsJson
# Output: OneTamilMVProvider/build/OneTamilMVProvider.cs3 and build/plugins.json
```

Or deploy straight to a phone/emulator with CloudStream installed:

```bash
./gradlew OneTamilMVProvider:deployWithAdb
```

## Layout

```
build.gradle.kts                  Root build (official TestPlugins template)
settings.gradle.kts               Auto-includes any folder with a build file
repo.json                         Repository manifest CloudStream reads
domains.json                      Known 1TamilMV domains (see above)
.github/workflows/build.yml       Builds .cs3 + plugins.json to `builds`
OneTamilMVProvider/
  build.gradle.kts                Plugin metadata (name, status, types, lang)
  src/main/AndroidManifest.xml
  src/main/kotlin/com/onetamilmv/
    OneTamilMVPlugin.kt           Registers the provider
    OneTamilMVProvider.kt         Main page, search, load, loadLinks
```

## Known limits (v1)

- Search first tries XenForo's search endpoint; on mirrors that require
  a login for search it falls back to matching titles from the latest
  threads list, so very old titles may not be found by search — they are
  still reachable by browsing if the mirror lists them.
- Listing pages have no poster (posters live inside each thread);
  the poster and plot are filled in when you open a title.
- Selectors target standard XenForo markup (`structItem--thread`,
  `bbWrapper`, first `article.message-body`). If a mirror runs a heavily
  customised theme, the selectors in `OneTamilMVProvider.kt` are the
  place to adjust.
