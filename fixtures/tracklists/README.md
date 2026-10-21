# Tracklists

Albums and one artist's playlist, for `:cli`'s `--tracklist` (`Tracklist` in `:cli`): consecutive
tracks by the same artist, to measure what the insights send and write about the artist from one
track to the next ([#239](https://github.com/wkusnierczyk/sleevesight/issues/239)). Each track is
given as a player would report it: the title (with Spotify's version suffix where it has one, such
as `- Remastered 2009`), the artists, the release and the length.

| File | Tracks |
|---|---|
| `rubber-soul.json` | The Beatles, *Rubber Soul*, in order: most of its songs have a Wikipedia article |
| `sundown.json` | Gordon Lightfoot, *Sundown*, in order: few of its songs have an article, so the artist's lines are most of each track's evidence |
| `punisher.json` | Phoebe Bridgers, *Punisher*, in order: a recent album |
| `radiohead.json` | Radiohead, tracks from several albums: one artist's playlist |

The titles, track order and lengths are from MusicBrainz's releases (core data, CC0) and the
releases' usual streaming editions, read on 2026-10-21; a length may differ from a given edition's
by a few seconds, within the resolver's tolerance.
