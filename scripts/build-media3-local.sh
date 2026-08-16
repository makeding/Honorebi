#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"
media3_dir="$repo_root/third_party/media3"

# shellcheck source=/dev/null
source "$media3_dir/upstream.env"

case "$MEDIA3_UPSTREAM_VERSION:$MEDIA3_LOCAL_VERSION" in
  *[!0-9A-Za-z._:+-]*)
    echo "Invalid Media3 version configuration" >&2
    exit 2
    ;;
esac
if [[ ! "$MEDIA3_UPSTREAM_COMMIT" =~ ^[0-9a-f]{40}$ ]]; then
  echo "MEDIA3_UPSTREAM_COMMIT must be a full Git commit" >&2
  exit 2
fi

check_only=false
if [[ "${1:-}" == "--check" ]]; then
  check_only=true
elif [[ $# -ne 0 ]]; then
  echo "Usage: $0 [--check]" >&2
  exit 2
fi

temp_root="${TMPDIR:-/tmp}"
work_root="$temp_root/komorebi-media3-build"
source_dir="$work_root/androidx-media"
staging_repo="$work_root/maven"
work_budget_kib=$((1536 * 1024))
monitor_pid=""
cleanup() {
  if [[ -n "$monitor_pid" ]]; then
    kill "$monitor_pid" 2>/dev/null || true
    wait "$monitor_pid" 2>/dev/null || true
  fi
  local removed_kib=0
  if [[ -d "$work_root" ]]; then
    removed_kib="$(du -sk "$work_root" | awk '{print $1}')"
  fi
  rm -rf -- "$work_root"
  [[ ! -e "$work_root" ]]
  echo "Cleaned Media3 build work directory (${removed_kib} KiB): $work_root"
}
trap cleanup EXIT
trap 'exit 130' INT TERM

if [[ -e "$work_root" ]]; then
  rm -rf -- "$work_root"
fi
mkdir -p "$work_root"

parent_pid="$$"
(
  trap - EXIT INT TERM
  while kill -0 "$parent_pid" 2>/dev/null; do
    current_kib="$(du -sk "$work_root" 2>/dev/null | awk '{print $1}' || true)"
    if [[ -z "$current_kib" ]]; then
      sleep 2
      continue
    fi
    if (( current_kib > work_budget_kib )); then
      echo "Media3 build work directory exceeded 1.5 GiB: ${current_kib} KiB" >&2
      kill -TERM "$parent_pid"
      exit 1
    fi
    sleep 2
  done
) &
monitor_pid="$!"

git clone --filter=blob:none --no-checkout --sparse https://github.com/androidx/media.git "$source_dir"
git -C "$source_dir" fetch --depth 1 origin "$MEDIA3_UPSTREAM_COMMIT"
git -C "$source_dir" sparse-checkout set \
  buildSrc \
  gradle \
  libraries/common \
  libraries/container \
  libraries/database \
  libraries/datasource \
  libraries/decoder \
  libraries/effect \
  libraries/extractor \
  libraries/exoplayer \
  libraries/exoplayer_hls \
  libraries/session \
  libraries/ui
git -C "$source_dir" checkout --detach "$MEDIA3_UPSTREAM_COMMIT"

# The sources and API docs are already in the checkout. The smaller Gradle binary
# distribution keeps this disposable build below its enforced disk budget.
perl -pi -e 's/-all\.zip/-bin.zip/' "$source_dir/gradle/wrapper/gradle-wrapper.properties"

actual_commit="$(git -C "$source_dir" rev-parse HEAD)"
if [[ "$actual_commit" != "$MEDIA3_UPSTREAM_COMMIT" ]]; then
  echo "Expected Media3 $MEDIA3_UPSTREAM_COMMIT, got $actual_commit" >&2
  exit 1
fi

while IFS= read -r patch_name; do
  [[ -z "$patch_name" || "$patch_name" == \#* ]] && continue
  patch_path="$media3_dir/patches/$patch_name"
  echo "Applying $patch_name"
  git -C "$source_dir" apply --check "$patch_path"
  git -C "$source_dir" apply "$patch_path"
done < "$media3_dir/series"

upstream_version_line="    releaseVersion = '$MEDIA3_UPSTREAM_VERSION'"
local_version_line="    releaseVersion = '$MEDIA3_LOCAL_VERSION'"
if ! grep -Fqx "$upstream_version_line" "$source_dir/constants.gradle"; then
  echo "Unable to locate the upstream releaseVersion in constants.gradle" >&2
  exit 1
fi
sed "s/^${upstream_version_line}$/${local_version_line}/" \
  "$source_dir/constants.gradle" > "$work_root/constants.gradle"
mv "$work_root/constants.gradle" "$source_dir/constants.gradle"
grep -Fqx "$local_version_line" "$source_dir/constants.gradle"

if $check_only; then
  echo "Media3 $MEDIA3_UPSTREAM_VERSION patch stack applies cleanly."
  exit 0
fi

: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK path}"
export ANDROID_USER_HOME="${ANDROID_USER_HOME:-$work_root/android-home}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$work_root/gradle-home}"
export GRADLE_OPTS="${GRADLE_OPTS:-} -Duser.home=$work_root/user-home"
mkdir -p "$ANDROID_USER_HOME" "$GRADLE_USER_HOME" "$work_root/user-home"

gradle_tasks=(
  :lib-common:publishReleasePublicationToMavenRepository
  :lib-container:publishReleasePublicationToMavenRepository
  :lib-database:publishReleasePublicationToMavenRepository
  :lib-datasource:publishReleasePublicationToMavenRepository
  :lib-decoder:publishReleasePublicationToMavenRepository
  :lib-effect:publishReleasePublicationToMavenRepository
  :lib-extractor:publishReleasePublicationToMavenRepository
  :lib-exoplayer:publishReleasePublicationToMavenRepository
  :lib-exoplayer-hls:publishReleasePublicationToMavenRepository
  :lib-session:publishReleasePublicationToMavenRepository
  :lib-ui:publishReleasePublicationToMavenRepository
)

"$source_dir/gradlew" \
  --project-dir "$source_dir" \
  "${gradle_tasks[@]}" \
  -PreleaseVersion="$MEDIA3_LOCAL_VERSION" \
  -PmavenRepo="$staging_repo" \
  -Pkotlin.compiler.execution.strategy=in-process \
  --no-daemon \
  --no-watch-fs

rsync -a "$staging_repo/androidx/media3/" "$repo_root/local_repo/androidx/media3/"
echo "Published Media3 $MEDIA3_LOCAL_VERSION to $repo_root/local_repo"
