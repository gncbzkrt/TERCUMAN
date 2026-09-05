#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

SOURCE_DIR="$(cd "$(dirname "$0")" && pwd)"
TARGET_DIR="${1:-$HOME/TERCUMAN-v0.1.0-GITHUB}"

if [ ! -d "$TARGET_DIR/.git" ]; then
  echo "Hedef GitHub deposu bulunamadı: $TARGET_DIR"
  exit 1
fi

rsync -a --delete --exclude='.git/' "$SOURCE_DIR/" "$TARGET_DIR/"
cd "$TARGET_DIR"
rm -f TERMUX-DEPLOY-V1.5.0.sh

git add .
if git diff --cached --quiet; then
  echo "Değişiklik yok. Repo zaten v1.6.1 kaynaklarıyla aynı olabilir."
  exit 0
fi

git commit -m "TERCUMAN v1.6.1 regression fix — restore stable live ASR"
git push origin main

echo "TERCÜMAN v1.6.1 GitHub'a gönderildi."
