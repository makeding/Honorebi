# Media3 patch stack

Komorebi consumes an upstream AndroidX Media checkout plus the ordered patches in `series`.
Generated AARs are published under the `-komorebi` version in the repository's `local_repo`.
The upstream checkout itself is not vendored.

The patches preserve broadcast-specific behavior:

1. discard initial AAC/ADTS samples that arrive before the first PES timestamp;
2. resynchronize large broadcast audio timestamp discontinuities without reporting an audio-sink error;
3. avoid dropping late video frames or GOPs on the target Android TV and submit frames immediately.
4. expose the configured codec input format so HDR tone-map requests can be verified.

To bump Media3:

1. update `MEDIA3_UPSTREAM_VERSION`, `MEDIA3_UPSTREAM_COMMIT`, and `MEDIA3_LOCAL_VERSION` in `upstream.env`;
2. run `scripts/build-media3-local.sh --check`;
3. refresh only patches that no longer apply, preserving their behavior and order;
4. run `scripts/build-media3-local.sh` to test and publish the patched modules;
5. update the app's Media3 coordinate and run the Android/player test matrix.

`--check` clones the pinned commit and applies every patch with `git apply --check`, without building
or changing `local_repo`.
