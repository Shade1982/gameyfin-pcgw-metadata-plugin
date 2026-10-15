# Gameyfin PCGamingWiki Plugin

A metadata provider plugin for [Gameyfin](https://github.com/gameyfin/gameyfin)
that searches [PCGamingWiki](https://www.pcgamingwiki.com/) for game metadata.

## Features

- Searches PCGamingWiki game pages by title.
- Provides introductory descriptions and cover thumbnails when available.
- Extracts developer, publisher, release date, and supported platforms from the
	game's infobox.
- Uses the public MediaWiki API and caches fetched metadata in memory.

## Metadata lookup

The provider checks for an exact page title first, then searches PCGamingWiki
with MediaWiki pagination (up to five pages of 50 results). Exact title matches
are ranked first. Results with unknown platform data are retained, while known
platforms are filtered against the library's platform selection. Metadata is
cached in memory with a 256-entry least-recently-used limit. Empty searches and
platform-filtered results are reported at debug level; API failures are logged
as warnings.

## Build

Requires JDK 21 or newer.

```sh
./gradlew build
```

Run the focused parser, ranking, pagination, and cache tests with:

```sh
./gradlew test
```

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