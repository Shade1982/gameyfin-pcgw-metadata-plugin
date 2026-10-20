# Gameyfin PCGamingWiki Plugin

A metadata provider plugin for [Gameyfin](https://github.com/gameyfin/gameyfin)
that searches [PCGamingWiki](https://www.pcgamingwiki.com/) for game metadata.

## Features

- Searches PCGamingWiki game pages by title.
- Provides introductory descriptions when available. Images are not provided:
  PCGamingWiki's image host (`images.pcgamingwiki.com`) is protected by a
  Cloudflare challenge and returns an HTML page instead of the image to
  server-side downloads. Gameyfin would store that page as the cover, replacing
  a working image from another provider.
- Extracts developer, publisher, release date, and supported platforms from the
	game's infobox.
- Uses the public MediaWiki API and caches fetched metadata in memory.

## Metadata lookup

The provider checks for an exact page title first, then searches PCGamingWiki
with MediaWiki pagination (up to five pages of 50 results). Search results are
only accepted when the title matches the query ignoring case and punctuation,
is very similar to it, or continues it with more words (for example "Portal 2"
matches "Portal 2: Community Edition"). Generic queries such as "Age of" or
"The" therefore match nothing instead of an arbitrary game. Results with
unknown platform data are retained, while known
platforms are filtered against the library's platform selection. Metadata is
cached in memory with a 256-entry least-recently-used limit. Empty searches and
platform-filtered results are reported at debug level; API failures are logged
as warnings.

## Using with other providers

Gameyfin merges results field by field: for each field, the first provider with
a non-empty value wins, in plugin priority order. Values are never combined, so
a single genre from this plugin replaces a longer list from another provider.
PCGamingWiki is best used as a supplement, so place it below your primary
providers (for example IGDB or Steam) in the plugin overview.

## Build

Requires JDK 25 or newer to compile against the Gameyfin 2.5.0 plugin API.

```sh
./gradlew build
```

Run the focused parser, ranking, pagination, and cache tests with:

```sh
./gradlew test
```

The test task also checks that the packaged JAR contains the PF4J extension
index and can be loaded with the Gameyfin 2.5.0 `PluginContext` API. CI runs
these checks with JDK 25.

## Installation

1.  Download the latest release jar (pcgamingwiki-plugin-x.y.z.jar) from the Releases page.
2.  Navigate to your Gameyfin installation directory.
3.  Place the .jar file into the plugins/ folder.
    *   *Note: If the plugins folder does not exist, create it.*
4.  Restart Gameyfin.

### Docker Compose

If you are running Gameyfin using Docker Compose, you can mount the plugin JAR file as a volume.

```yaml
services:
  gameyfin:
    # ... other configuration ...
    volumes:
      - /your/location/pcgamingwiki-plugin-x.x.x.jar:/opt/gameyfin/plugins/pcgamingwiki-plugin-x.x.x.jar
```