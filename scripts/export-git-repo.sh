#!/bin/bash

# 用法: ./clone-git-repo.sh <git仓库路径或URL> <目标目录> [--branch <分支名>] [--depth <深度>]
#
# 示例:
#   ./clone-git-repo.sh /path/to/source-repo /path/to/target-dir
#   ./clone-git-repo.sh https://github.com/user/repo.git /path/to/target-dir --branch main --depth 1
#

set -e

# 参数解析
GIT_SOURCE=""
TARGET_DIR=""
BRANCH=""
DEPTH=""

while [[ $# -gt 0 ]]; do
    case $1 in
        --branch)
            BRANCH="$2"
            shift 2
            ;;
        --depth)
            DEPTH="$2"
            shift 2
            ;;
        -*)
            echo "未知参数: $1"
            exit 1
            ;;
        *)
            if [[ -z "$GIT_SOURCE" ]]; then
                GIT_SOURCE="$1"
            else
                TARGET_DIR="$1"
            fi
            shift
            ;;
    esac
done

# 验证参数
if [[ -z "$GIT_SOURCE" ]]; then
    echo "错误: 请提供Git仓库路径或URL"
    echo "用法: $0 <git仓库路径或URL> <目标目录> [--branch <分支名>] [--depth <深度>]"
    exit 1
fi

if [[ -z "$TARGET_DIR" ]]; then
    echo "错误: 请提供目标目录"
    echo "用法: $0 <git仓库路径或URL> <目标目录> [--branch <分支名>] [--depth <深度>]"
    exit 1
fi

# 创建临时工作目录
TEMP_DIR=$(mktemp -d)
TRAP_CMD="rm -rf $TEMP_DIR"
trap "$TRAP_CMD" EXIT

echo "=== Git仓库代码同步脚本 ==="
echo "源仓库: $GIT_SOURCE"
echo "目标目录: $TARGET_DIR"
echo "临时目录: $TEMP_DIR"
echo ""

# 检查源是否是本地路径
if [[ -d "$GIT_SOURCE/.git" ]]; then
    echo "检测到本地Git仓库..."
    SOURCE_TYPE="local"
    REPO_DIR="$GIT_SOURCE"
elif [[ "$GIT_SOURCE" =~ ^https?:// ]] || [[ "$GIT_SOURCE" =~ ^git@ ]]; then
    echo "检测到远程Git仓库..."
    SOURCE_TYPE="remote"
    REPO_DIR="$TEMP_DIR/repo"
else
    echo "错误: 无法识别的仓库路径/URL: $GIT_SOURCE"
    exit 1
fi

# 如果是远程仓库，先克隆到临时目录
if [[ "$SOURCE_TYPE" == "remote" ]]; then
    echo "正在克隆Git仓库..."
    CLONE_CMD="git clone --no-checkout $GIT_SOURCE $REPO_DIR"
    if [[ -n "$BRANCH" ]]; then
        CLONE_CMD="$CLONE_CMD -b $BRANCH"
    fi
    if [[ -n "$DEPTH" ]]; then
        CLONE_CMD="$CLONE_CMD --depth $DEPTH"
    fi
    eval "$CLONE_CMD"
else
    # 本地仓库
    REPO_DIR=$(realpath "$GIT_SOURCE")
fi

# 确认目标目录存在，并清空
echo ""
echo "正在准备目标目录..."
if [[ -d "$TARGET_DIR" ]]; then
    # 检查目录是否非空
    if [[ -n "$(ls -A "$TARGET_DIR" 2>/dev/null)" ]]; then
        echo "警告: 目标目录 '$TARGET_DIR' 非空"
        read -p "是否清空目标目录并继续? (y/N): " -n 1 -r
        echo
        if [[ ! $REPLY =~ ^[Yy]$ ]]; then
            echo "已取消操作"
            exit 0
        fi
    fi
    echo "清空目标目录: $TARGET_DIR"
    rm -rf "$TARGET_DIR"/*
else
    echo "创建目标目录: $TARGET_DIR"
    mkdir -p "$TARGET_DIR"
fi

# 使用 git archive 导出（会自动忽略 .gitignore 中的文件）
echo ""
echo "正在导出代码（忽略 .gitignore 中的文件）..."
cd "$REPO_DIR"

# 获取当前分支或指定的commit/branch
GIT_REF="${BRANCH:-HEAD}"

# 使用 git archive 导出并解压到目标目录
# git archive 会自动排除被 .gitignore 忽略的文件
git archive "$GIT_REF" | tar -x -C "$TARGET_DIR"

# 同时导出 .gitignore 文件（如果存在）因为通常会被包含在源码分发中
if [[ -f ".gitignore" ]]; then
    cp ".gitignore" "$TARGET_DIR/"
fi

cd - > /dev/null

echo ""
echo "=== 完成 ==="
echo "已将代码同步到: $TARGET_DIR"
echo ""
echo "目标目录内容:"
ls -la "$TARGET_DIR"
