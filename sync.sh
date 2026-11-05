#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# E-Ink Launcher 改造版 · 一键同步到 GitHub
#
# 用法：
#   ./sync.sh "这次改了什么"              编译 + 提交 + 推送到 GitHub
#   ./sync.sh --no-build "改了文档"       跳过编译，只提交代码/文档
#   ./sync.sh --release "本次改动要点"    升版本 + 编译 + 更新 README + 提交推送 + 发 Release
#   ./sync.sh --dry-run "..."             只打印要做的事，不动仓库
#
# 说明：
#   - 远端固定用 publish（origin 指向上游 Modificator 仓库，别推错）
#   - 仓库里 .gitignore 已忽略 *.apk，APK 只进 Release，不进 git
#   - Release 走 REST API（gh CLI 的 release 子命令会因缺 workflow scope 失败）
# ---------------------------------------------------------------------------
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$REPO_DIR"

REMOTE="publish"
BRANCH="main"
GRADLE_FILE="app/build.gradle.kts"
TOOLCHAIN_JAVA="D:/hisense_root/toolchain/jdk-17.0.21+9"
DELIVER_DIR="D:/hisense_root/deliver"
TEMPLATE="docs/release-notes-template.md"

BUILD=1
RELEASE=0
DRY=0
MSG=""

usage() {
  sed -n '3,14p' "$0" | sed 's/^# \{0,1\}//'
}

while [ $# -gt 0 ]; do
  case "$1" in
    --no-build) BUILD=0 ;;
    --release)  RELEASE=1 ;;
    --dry-run)  DRY=1 ;;
    -h|--help)  usage; exit 0 ;;
    *)          MSG="${MSG:+$MSG }$1" ;;
  esac
  shift
done

say()  { printf '\n\033[1m==> %s\033[0m\n' "$*"; }
warn() { printf '\033[33m[!] %s\033[0m\n' "$*"; }

run() {
  if [ "$DRY" -eq 1 ]; then
    printf '   [dry-run] %s\n' "$*"
  else
    "$@"
  fi
}

# ---------------------------------------------------------------- 环境检查
if ! git rev-parse --git-dir >/dev/null 2>&1; then
  echo "这里不是 git 仓库：$REPO_DIR"; exit 1
fi

if [ -z "$(git remote get-url "$REMOTE" 2>/dev/null)" ]; then
  echo "找不到远端 $REMOTE，先确认 git remote -v"; exit 1
fi
SLUG="$(git remote get-url "$REMOTE" | sed -E 's#.*github\.com[:/]##; s#\.git$##')"

read -r VER CODE <<<"$(python - "$GRADLE_FILE" <<'PY'
import re, sys
s = open(sys.argv[1], encoding="utf-8").read()
v = re.search(r'versionName\s*=\s*"([^"]+)"', s).group(1)
c = re.search(r'versionCode\s*=\s*(\d+)', s).group(1)
print(v, c)
PY
)"

say "仓库 $SLUG　分支 $BRANCH　当前版本 $VER (code $CODE)"

# ---------------------------------------------------------------- 升版本
if [ "$RELEASE" -eq 1 ]; then
  NEWVER="$(python - "$VER" <<'PY'
import re, sys
v = sys.argv[1]
m = re.match(r'^(.*?)(\d+)$', v)
print("{}{}".format(m.group(1), int(m.group(2)) + 1) if m else v + "2")
PY
)"
  NEWCODE=$((CODE + 1))
  say "升版本：$VER ($CODE)  ->  $NEWVER ($NEWCODE)"
  if [ "$DRY" -eq 0 ]; then
    python - "$GRADLE_FILE" "$NEWVER" "$NEWCODE" <<'PY'
import re, sys
path, ver, code = sys.argv[1], sys.argv[2], sys.argv[3]
s = open(path, encoding="utf-8").read()
s = re.sub(r'versionName\s*=\s*"[^"]+"', 'versionName = "%s"' % ver, s, count=1)
s = re.sub(r'versionCode\s*=\s*\d+', 'versionCode = %s' % code, s, count=1)
open(path, "w", encoding="utf-8").write(s)
PY
    # README：顶部下载链接 + 变更记录插一行
    python - README.md "$NEWVER" "E-Ink-Launcher-Folders-${NEWVER}.apk" "$MSG" <<'PY'
import re, sys
path, ver, apk, note = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
s = open(path, encoding="utf-8").read()
s = re.sub(r'E-Ink-Launcher-Folders-[0-9A-Za-z._-]+\.apk', apk, s)
note = (note or "发布 " + ver).replace("|", "/")
row = "| {} | {} |\n".format(ver, note)
anchor = "| 版本 | 内容 |\n|---|---|\n"
if anchor in s and row not in s:
    s = s.replace(anchor, anchor + row, 1)
open(path, "w", encoding="utf-8").write(s)
PY
  fi
  FINALVER="$NEWVER"
else
  FINALVER="$VER"
fi

APK_NAME="E-Ink-Launcher-Folders-${FINALVER}.apk"

# ---------------------------------------------------------------- 编译
if [ "$BUILD" -eq 1 ]; then
  if [ -z "${JAVA_HOME:-}" ] || [ ! -f "$JAVA_HOME/bin/javac.exe" ]; then
    export JAVA_HOME="$TOOLCHAIN_JAVA"
  fi
  if [ ! -f "$JAVA_HOME/bin/javac.exe" ]; then
    echo "找不到 JDK（$JAVA_HOME），装好后重试，或用 --no-build 跳过编译"; exit 1
  fi
  say "编译 assembleDebug（JAVA_HOME=$JAVA_HOME）"
  if [ "$DRY" -eq 1 ]; then
    printf '   [dry-run] gradlew assembleDebug\n'
  else
    OUT="$(cmd //c "gradlew.bat assembleDebug --console=plain" 2>&1 | tail -30)"
    echo "$OUT" | tail -12
    if ! echo "$OUT" | grep -q "BUILD SUCCESSFUL"; then
      echo "编译失败，已中止（没有提交、没有推送）"; exit 1
    fi
    APK_SRC="app/build/outputs/apk/debug/app-debug.apk"
    [ -f "$APK_SRC" ] || { echo "找不到产物 $APK_SRC"; exit 1; }
    mkdir -p "$DELIVER_DIR"
    cp "$APK_SRC" "$DELIVER_DIR/桌面App-2x2文件夹-${FINALVER}.apk"
    cp "$APK_SRC" "/tmp/$APK_NAME"
    echo "   产物：$APK_SRC（$(du -h "$APK_SRC" | cut -f1)）"
    echo "   已复制：$DELIVER_DIR/桌面App-2x2文件夹-${FINALVER}.apk"
  fi
fi

# ---------------------------------------------------------------- 提交 + 推送
COMMIT_MSG="${MSG:-sync: 同步到 GitHub $(date '+%Y-%m-%d %H:%M')}"
if [ "$RELEASE" -eq 1 ] && [ -z "$MSG" ]; then
  COMMIT_MSG="release: v${FINALVER}"
fi

if [ "$DRY" -eq 1 ]; then
  warn "dry-run：跳过 git add，索引保持原样"
else
  git add -A
fi
if [ "$DRY" -eq 0 ] && git diff --cached --quiet; then
  warn "没有需要提交的改动"
else
  say "提交：$COMMIT_MSG"
  if [ "$DRY" -eq 0 ]; then
    git -c user.email=local@local -c user.name=local commit -q -m "$COMMIT_MSG"
    git log --oneline -1
  fi
fi

AHEAD="$(git rev-list --count "$REMOTE/$BRANCH..$BRANCH" 2>/dev/null || echo 0)"
if [ "$AHEAD" -gt 0 ] || [ "$DRY" -eq 1 ]; then
  say "推送到 $REMOTE/$BRANCH（领先 $AHEAD 个提交）"
  run git push "$REMOTE" "$BRANCH"
else
  echo "   与远端一致，无需推送"
fi

# ---------------------------------------------------------------- 发 Release
if [ "$RELEASE" -eq 1 ]; then
  TAG="v${FINALVER}"
  say "发布 Release $TAG"

  if [ "$DRY" -eq 1 ]; then
    printf '   [dry-run] 生成 %s 并上传 %s\n' "$TAG" "$APK_NAME"
    exit 0
  fi

  # 生成说明
  BODY_FILE="$(mktemp)"
  python - "$TEMPLATE" "$BODY_FILE" "$FINALVER" "$APK_NAME" "$COMMIT_MSG" <<'PY'
import sys
tpl, out, ver, apk, changes = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4], sys.argv[5]
s = open(tpl, encoding="utf-8").read()
s = s.replace("{{VERSION}}", ver).replace("{{APK}}", apk).replace("{{CHANGES}}", "- " + changes)
open(out, "w", encoding="utf-8").write(s)
PY

  JSON_FILE="$(mktemp)"
  python - "$TAG" "$BODY_FILE" "$JSON_FILE" <<'PY'
import json, sys
tag, body_file, out = sys.argv[1], sys.argv[2], sys.argv[3]
body = open(body_file, encoding="utf-8").read()
json.dump({"tag_name": tag, "name": tag, "body": body,
           "draft": False, "prerelease": False},
          open(out, "w", encoding="utf-8"), ensure_ascii=False)
PY

  REL_ID="$(gh api "repos/$SLUG/releases/tags/$TAG" --jq '.id' 2>/dev/null || true)"
  if [ -z "$REL_ID" ]; then
    REL_ID="$(gh api -X POST "repos/$SLUG/releases" --input "$JSON_FILE" --jq '.id')"
    echo "   新建 Release id=$REL_ID"
  else
    gh api -X PATCH "repos/$SLUG/releases/$REL_ID" --input "$JSON_FILE" --jq '.id' >/dev/null
    echo "   Release 已存在，更新说明 id=$REL_ID"
  fi
  rm -f "$JSON_FILE" 2>/dev/null || true

  # 同名附件先删再传
  OLD_ASSET="$(gh api "repos/$SLUG/releases/$REL_ID/assets" \
    --jq ".[] | select(.name==\"$APK_NAME\") | .id" 2>/dev/null || true)"
  if [ -n "$OLD_ASSET" ]; then
    gh api -X DELETE "repos/$SLUG/releases/assets/$OLD_ASSET" >/dev/null 2>&1 || true
  fi

  curl -s -X POST \
    -H "Authorization: token $(gh auth token)" \
    -H "Content-Type: application/vnd.android.package-archive" \
    --data-binary @"/tmp/$APK_NAME" \
    "https://uploads.github.com/repos/$SLUG/releases/$REL_ID/assets?name=$APK_NAME" \
    | python -c "import sys,json;d=json.load(sys.stdin);print('   已上传：{} ({} B)'.format(d.get('name'), d.get('size')))"

  rm -f "$BODY_FILE" 2>/dev/null || true
  echo "   https://github.com/$SLUG/releases/tag/$TAG"
fi

say "完成"
