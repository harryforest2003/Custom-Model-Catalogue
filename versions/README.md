# Version targets

One file per Minecraft version the mod is built for. `./build-all.sh` reads
every file here, runs a build for each, and collects the jars into
`build/dist/`, named with the version they are for:

    custom-model-viewer-1.0.0+26.2.jar

A plain `./gradlew build` still builds the version named in `gradle.properties`
and nothing else, so the ordinary edit-and-run loop is unchanged. To build one
other target without the whole set, pass the same values by hand:

    ./gradlew build -Pminecraft_version=1.21.11 -Pjava_release=21 -Pmojang_mappings=true ...

## Adding a version

Copy a file, change the values, and check it builds. The values are:

| Key | What it is |
| --- | --- |
| `minecraft_version` | What Loom fetches. Must be a real Loom version. |
| `minecraft_range` | What `fabric.mod.json` will refuse to load outside of. |
| `loader_version` | Minimum Fabric Loader. |
| `fabric_api_version` | The Fabric API build for that Minecraft version. |
| `loom_version` | Loom is version-specific too — see below. |
| `java_release` | 17 for 1.20.x, 21 for 1.21.x, 25 for 26.x. |
| `mojang_mappings` | `false` for 26.1+, which ship unobfuscated. `true` before that. |

Look the first four up at <https://fabricmc.net/develop/>.

`loom_version` is in the list because Loom itself is a version target, not a
constant. It is read in the `plugins` block, and `-Ploom_version=...` does reach
there — confirmed with `./gradlew buildEnvironment -Ploom_version=1.16.3`, which
resolves `net.fabricmc:fabric-loom:1.16.3`. Whichever Loom a target needs, it
asks for here.

**Adding a file is not the same as the mod working there.** The source has to
compile against that version's API, and before 1.21.4 large parts of it cannot
— see `PORTING.md` for what each version would actually take. A version whose
build fails will fail loudly rather than quietly producing a broken jar.
