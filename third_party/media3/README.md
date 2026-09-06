# Media3 patch stack

Komorebi consumes an upstream AndroidX Media checkout plus the ordered patches in `series`.
Generated AARs are published under the `-komorebi` version in the repository's `local_repo`.
The upstream checkout itself is not vendored.

The unmodified `media3-datasource-okhttp` adapter comes from Google Maven at the same
upstream base version (the app derives it from the `-komorebi` version). It has no
broadcast patch; its transitive Media3 modules still resolve to our local patched
artifacts. HTTP/HLS traffic uses the app's scoped Access client through this adapter.

The patches preserve broadcast-specific behavior:

1. retain complete AAC/ADTS bytes across PES boundaries and suppress metadata only for frames that finish without a timestamp;
2. resynchronize large broadcast audio timestamp discontinuities without reporting an audio-sink error;
3. avoid dropping late video frames or GOPs on the target Android TV, submitting direct output
   immediately while preserving timestamped release into a video graph;
4. apply a caller-provided HLG-to-SDR LUT while sampling the decoder's external YUV texture,
   before Media3 performs any built-in HDR transform.

To bump Media3:

1. update `MEDIA3_UPSTREAM_VERSION`, `MEDIA3_UPSTREAM_COMMIT`, and `MEDIA3_LOCAL_VERSION` in `upstream.env`;
2. run `scripts/build-media3-local.sh --check`;
3. refresh only patches that no longer apply, preserving their behavior and order;
4. run `scripts/build-media3-local.sh` to test and publish the patched modules;
5. update the app's Media3 coordinate and run the Android/player test matrix.

`--check` uses a sparse checkout of the pinned commit and applies every patch with
`git apply --check`, without building or changing `local_repo`. The build uses one deterministic
system-temporary path, enforces a 1 GiB limit, and removes the checkout, caches, and staging
repository on success, failure, or interruption.
