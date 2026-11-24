#!/bin/sh
# Download pinned PaddleOCR PP-OCRv6_small ONNX assets and verify checksums.
# No Python, no model conversion, no runtime downloads: run once, bundle results.
# Usage: sh tools/download-models.sh   (from the repository root)
set -eu

DET_SHA=28fe5895c24fd108c19eb3e8479f4ab385fbfc62
REC_SHA=b8f84f0b80c529de40b4fbb3544b84fa7233a513
DET_PINNED="https://huggingface.co/PaddlePaddle/PP-OCRv6_small_det_onnx/resolve/$DET_SHA"
REC_PINNED="https://huggingface.co/PaddlePaddle/PP-OCRv6_small_rec_onnx/resolve/$REC_SHA"
DET_BRANCH="https://huggingface.co/PaddlePaddle/PP-OCRv6_small_det_onnx/resolve/main"
REC_BRANCH="https://huggingface.co/PaddlePaddle/PP-OCRv6_small_rec_onnx/resolve/main"
CHECKSUMS="checksums.sha256"

want_hash() {
    awk -v f="$1" '$2 == f { print $1; exit }' "$CHECKSUMS"
}

get_one() {
    pinned="$1"
    branch="$2"
    dest="$3"
    tmp="$dest.part"
    rm -f "$tmp"
    if [ -f "$dest" ] && [ -f "$CHECKSUMS" ]; then
        want="$(want_hash "$dest")"
        have="$(sha256sum "$dest" | awk '{ print $1 }')"
        if [ -n "$want" ] && [ "$want" = "$have" ]; then
            echo " keeping existing valid $dest"
            return 0
        fi
    fi
    # Pinned commit SHA first; the host intermittently answers SHA resolves
    # with "Invalid rev id" while the branch ref works, so fall back to the
    # branch. Byte integrity is enforced by checksums.sha256 either way.
    if ! curl -fSL --retry 4 --retry-all-errors --retry-delay 10 -o "$tmp" "$pinned"; then
        echo " pinned revision failed for $dest, trying branch ref"
        curl -fSL --retry 4 --retry-all-errors --retry-delay 10 -o "$tmp" "$branch"
    fi
    mv "$tmp" "$dest"
    # Pace requests: the CDN answers rapid successive resolves with 404s.
    sleep 10
}

mkdir -p models
cd models
get_one "$DET_PINNED/inference.onnx" "$DET_BRANCH/inference.onnx" det.onnx
get_one "$DET_PINNED/inference.yml"  "$DET_BRANCH/inference.yml"  det.yml
get_one "$REC_PINNED/inference.onnx" "$REC_BRANCH/inference.onnx" rec.onnx
get_one "$REC_PINNED/inference.yml"  "$REC_BRANCH/inference.yml"  rec.yml
sha256sum -c "$CHECKSUMS"
echo "Models ready in $(pwd)"
