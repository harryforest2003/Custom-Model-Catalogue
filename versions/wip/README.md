# Targets that do not build yet

Kept out of `versions/` so `build-all.sh` stays honest: everything in that
folder builds, everything here is a start on one that does not.

## 1.21.4

Reached 171 errors with 1.21.11's table copied over, which is mostly one
discovery: **`ResourceLocation` was renamed to `Identifier` somewhere between
1.21.4 and 1.21.11**, so it accounts for 178 of them by itself. The rest, as
measured:

| Count | Symbol | Note |
| --- | --- | --- |
| 178 | `Identifier` | `net.minecraft.resources.ResourceLocation` here |
| 24 | `ItemOwner` | Did not exist yet |
| 10 | `AtlasIds` | Moved or named differently |
| 8 | `MouseButtonEvent` | `net.minecraft.client.input` is absent entirely |
| 8 | `getSelectedSlot()` | Inventory method renamed |
| 8 + 6 | `getStringOr` / `getBooleanOr` | Compound tag accessors changed |
| 4 | `RenderPipelines` | Render layer API differs |
| 4 | `packVersion(PackType)` | Pack format lookup differs |
| 4 | `SharedState` | Part of the reload API |

Plus `net.fabricmc.fabric.api.resource.v1`, so 1.21.4 needs its own
`ReloadHook` overlay exactly as 1.21.11 does — copy that one and adjust.

Most of this is more of the same: names. `ItemOwner` is the one to look at
first, because a type that did not exist yet may mean a signature that differs
rather than a word that changed, and that is the kind that needs a file rather
than a line.
