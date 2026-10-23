#!/usr/bin/env bash
#
# Builds the mod for every version in versions/, and collects the jars into
# build/dist/ named with the version they are for.
#
# A shell loop rather than a Gradle task on purpose: each target needs its own
# Java release and its own mapping set, which means a separate Gradle
# invocation, and a build that invokes itself fights its own lock on the
# project cache. One process at a time is both simpler and honest about what is
# happening.
set -euo pipefail

cd "$(dirname "$0")"

dist="build/dist"
rm -rf "$dist"
mkdir -p "$dist"

shopt -s nullglob
files=(versions/*.properties)
if [ ${#files[@]} -eq 0 ]; then
    echo "No version files in versions/ — nothing to build." >&2
    exit 1
fi

failed=()
built=()

for f in "${files[@]}"; do
    # Every key in the file becomes -Pkey=value, so a version target is
    # described in one place and nowhere else needs to know what is in it.
    args=()
    mc=""
    while IFS='=' read -r key value; do
        case "$key" in ''|\#*) continue ;; esac
        args+=("-P${key}=${value}")
        [ "$key" = "minecraft_version" ] && mc="$value"
    done < "$f"

    echo
    echo "=================================================================="
    echo "  Minecraft ${mc}   (${f})"
    echo "=================================================================="

    if ./gradlew build --console=plain "${args[@]}"; then
        # Named by the build itself as <name>-<mod version>+<mc version>.jar,
        # so picking them out needs no agreement between here and there beyond
        # the version being in the name.
        for jar in build/libs/*+"${mc}".jar; do
            case "$jar" in *-sources.jar) continue ;; esac
            cp "$jar" "$dist/"
            built+=("$(basename "$jar")")
        done
    else
        failed+=("$mc")
    fi
done

echo
echo "=================================================================="
if [ ${#built[@]} -gt 0 ]; then
    echo "Built into $dist:"
    for b in "${built[@]}"; do echo "  $b"; done
fi
if [ ${#failed[@]} -gt 0 ]; then
    echo
    echo "Failed:"
    for f in "${failed[@]}"; do echo "  Minecraft $f"; done
    echo
    echo "A version listed in versions/ is a version this mod is meant to build"
    echo "for, not one it is known to compile on. See PORTING.md."
    exit 1
fi
