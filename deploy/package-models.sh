#!/usr/bin/env bash
set -Eeuo pipefail

repository=${1:?Usage: package-models.sh OWNER/REPO RELEASE_TAG}
release_tag=${2:?Release tag is required}
mkdir -p build/model-manifest build/model-package release-assets
files=(local-model-artifact.json djl/vqvae/model.pt djl/vqvae/metadata.json
    djl/text/model.pt djl/text/metadata.json djl/emotional/model.pt djl/emotional/metadata.json
    thymos-6d/model.pt thymos-6d/tokenizer.json thymos-6d/metadata.json)
for path in "${files[@]}"; do
    [[ -s data/models/$path ]] || { echo "Missing production model file: $path" >&2; exit 1; }
done
# Stable archive bytes: unchanged models keep the same cache key across releases.
tar --sort=name --mtime=@0 --owner=0 --group=0 --numeric-owner \
    -cf - -C data/models "${files[@]}" | gzip -n -1 > build/model-package/models.tar.gz
digest=$(sha256sum build/model-package/models.tar.gz | cut -d ' ' -f 1)
filename="openeden-models-$digest.tar.gz"
mv build/model-package/models.tar.gz "release-assets/$filename"
printf '%s\n' "$digest" > build/model-manifest/sha256
printf 'https://github.com/%s/releases/download/%s/%s\n' "$repository" "$release_tag" "$filename" > build/model-manifest/url
echo "Prepared independent model bundle: $filename"
