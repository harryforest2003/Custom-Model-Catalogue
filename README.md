# Custom Model Catalogue

A client-side Fabric mod for Minecraft **26.2** that lets you look through the custom item models
in your resource packs.

It reads your packs, works out which vanilla item each custom model belongs to, groups the states
of one thing together, collapses duplicates, and renders every model in 3D from the pack's own
geometry and textures — **without enabling the pack, and without touching a single item you own.**

> **Viewer only.** This mod applies nothing. It writes no components to your items, adds no mixins,
> and is invisible to any server you play on. It is a catalogue of what your packs contain.
>
> It is the browsing half of [Custom Model Viewer](https://github.com/harryforest2003/CMV), with the
> wrap and skin application removed.

## What it does

**Reads every pack, not just the enabled ones.** Item model definitions are parsed directly —
`range_dispatch` on custom model data, `condition`, `select`, and nested dispatches — so a model is
findable whether or not the game would currently load it.

**Works out what each model is for.** Packs routinely hang unrelated models off whatever item was
convenient, so the item is inferred from the definition that dispatches to it, falling back to the
model's own path.

**Groups states.** A bow's `pulling_0/1/2` and a crossbow's loaded and charged states are one entry
with its states, not four unrelated results.

**Collapses duplicates** by hashing resolved geometry rather than file paths, so the same model
shipped in three packs under three names is one thing.

**Renders in 3D** with its own software rasteriser, so a model is viewable whether or not its pack
is on, and without stitching anything into the texture atlas. Turntable, free rotation, PNG export
and animated GIF export.

**Favourites and sets.** A set is a list you wrote, under a heading you named — nothing is inferred.
The mod used to guess sets from the packs and was wrong nearly every time, because these packs are
filed by event and contributor rather than by collection.

**Reports the atlas budget** — how many pixels your enabled packs would stitch against the real
limit, measured the way the game's own stitcher measures it: power-of-two, capped at 16384, and one
frame of an animated texture rather than the whole strip.

**Pack tools that are upstream of looking at models:** keep the resource pack a server sent you
(renamed after the server, reformatted and installed), repair a pack the game calls "broken or
incompatible", and put your packs back on when a failed reload switches them all off.

## Using it

| | |
| --- | --- |
| `/catalogue`, `/cat` | Open the catalogue |
| `/cmv`, `/modelexplorer` | Open the model index, optionally with a search |
| `/cmv rescan` | Read the packs again |
| `/cmv packs` | What is being scanned, and toggle a pack |
| `/cmv namespaces` | Filter a namespace out of the scan |
| **M** | Opens the model index |

Clicking a card opens that model's detail view — states, textures, geometry and export.

## Building

```
./gradlew build
```

The jar lands in `build/libs/`. `./build-all.sh` builds every target in `versions/` into
`build/dist/`, named for the game it is for.

## Tests

`./gradlew build` runs the check harnesses in `tools/` and fails the build if any check fails —
**571 checks** covering model classification, state grouping, deduplication, name resolution, the
rasteriser, the atlas arithmetic, the theme's contrast, and pack format repair.

They are plain classes with a `main` that print their own tally and exit non-zero, rather than
JUnit. Parsing somebody else's resource pack is full of quiet failures, and a wrong answer usually
looks like a plausible one.

## Licence

GNU GPL v3 or later, with an additional permission for linking with Minecraft and its mod
loaders (`LICENSE-EXCEPTION`). Use the code freely; if you publish anything built on it, publish its source under the
same terms and keep the copyright notice with it. See `LICENSE`, and `NOTICE` for the Apache-2.0
attribution covering algorithms adapted from
Custom Model Data Viewer.
