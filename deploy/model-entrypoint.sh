#!/usr/bin/env bash
set -Eeuo pipefail

manifest_dir=${OPENEDEN_MODEL_MANIFEST_DIR:-/opt/openeden-models}
model_root=${OPENEDEN_MODEL_CACHE_DIR:-/app/data/models}
digest=$(cat "$manifest_dir/sha256")
[[ $digest =~ ^[a-f0-9]{64}$ ]] || { echo 'Invalid model bundle digest' >&2; exit 1; }
url=${OPENEDEN_MODEL_DOWNLOAD_URL:-$(cat "$manifest_dir/url")}
bundle_dir="$model_root/bundles/$digest"
required=(local-model-artifact.json djl/vqvae/model.pt djl/vqvae/metadata.json
    djl/text/model.pt djl/text/metadata.json djl/emotional/model.pt djl/emotional/metadata.json
    thymos-6d/model.pt thymos-6d/tokenizer.json thymos-6d/metadata.json)

complete() {
    [[ -f $bundle_dir/.complete ]] || return 1
    local path
    for path in "${required[@]}"; do
        [[ -s $bundle_dir/$path ]] || return 1
    done
}

mkdir -p "$model_root/bundles"
# Release the lock before starting the server, so later containers can reuse the cache.
exec 9>"$model_root/.download.lock"
flock 9
if ! complete; then
    echo "Downloading model bundle $digest (first use of this model version)"
    archive="$model_root/$digest.tar.gz.part"
    curl --fail --location --retry 3 --connect-timeout 20 --max-time 3600 \
        --continue-at - --output "$archive" "$url"
    if ! printf '%s  %s\n' "$digest" "$archive" | sha256sum --check --status; then
        rm -f -- "$archive"
        echo 'Model bundle checksum mismatch; refusing to start' >&2
        exit 1
    fi
    staging=$(mktemp -d "$model_root/bundles/.unpack-XXXXXXXX")
    trap 'rm -rf -- "$staging"' EXIT
    tar --no-same-owner --no-same-permissions -xzf "$archive" -C "$staging"
    for path in "${required[@]}"; do
        [[ -s $staging/$path ]] || { echo "Model bundle missing $path" >&2; exit 1; }
    done
    touch "$staging/.complete"
    # A previously interrupted/incomplete extraction never counts as a valid cache.
    if [[ -e $bundle_dir ]]; then rm -rf -- "$bundle_dir"; fi
    mv -- "$staging" "$bundle_dir"
    trap - EXIT
    rm -f -- "$archive"
else
    echo "Using cached model bundle $digest"
fi
flock -u 9
exec 9>&-

export OPENEDEN_LOCAL_MODEL_ARTIFACT=${OPENEDEN_LOCAL_MODEL_ARTIFACT:-$bundle_dir/local-model-artifact.json}
export OPENEDEN_DJL_VQVAE_MODEL_PATH=${OPENEDEN_DJL_VQVAE_MODEL_PATH:-$bundle_dir/djl/vqvae}
export OPENEDEN_DJL_TEXT_MODEL_PATH=${OPENEDEN_DJL_TEXT_MODEL_PATH:-$bundle_dir/djl/text}
export OPENEDEN_DJL_EMOTIONAL_MODEL_PATH=${OPENEDEN_DJL_EMOTIONAL_MODEL_PATH:-$bundle_dir/djl/emotional}
export OPENEDEN_DJL_AFFECT_MODEL_PATH=${OPENEDEN_DJL_AFFECT_MODEL_PATH:-$bundle_dir/thymos-6d}
exec "$@"
