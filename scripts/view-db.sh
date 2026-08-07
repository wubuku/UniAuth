#!/usr/bin/env bash

# 查看 SQLite schema 和记录数，不输出表数据。
# 用法: DEV_DATABASE_FILE=/path/to/file.db ./view-db.sh [table_name]

set -euo pipefail

DB_FILE="${DEV_DATABASE_FILE:-./dev-database.db}"

if [ ! -f "$DB_FILE" ]; then
    echo "❌ 数据库文件 $DB_FILE 不存在"
    echo "请先启动应用以创建数据库"
    exit 1
fi

echo "📊 SQLite数据库: $DB_FILE"
echo "========================================"

# 检查sqlite3是否安装
if ! command -v sqlite3 &> /dev/null; then
    echo "❌ sqlite3 未安装，请安装后再试"
    echo "macOS: brew install sqlite"
    echo "Ubuntu: sudo apt install sqlite3"
    exit 1
fi

if [ $# -eq 0 ]; then
    # 显示所有表
    echo "📋 数据库中的表:"
    sqlite3 "$DB_FILE" ".tables"
    echo ""

    # 显示每张表的记录数
    echo "📈 表统计信息:"
    for table in $(sqlite3 "$DB_FILE" ".tables"); do
        count=$(sqlite3 "$DB_FILE" "SELECT COUNT(*) FROM $table;")
        echo "  $table: $count 条记录"
    done
    echo ""

else
    TABLE_NAME="$1"
    if [[ ! "${TABLE_NAME}" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]]; then
        echo "❌ 表名格式无效"
        exit 1
    fi

    echo "📋 表: $TABLE_NAME"
    echo "----------------------------------------"

    # 显示表结构
    echo "结构:"
    sqlite3 "$DB_FILE" ".schema $TABLE_NAME"
    echo ""

    # 统计行数
    COUNT=$(sqlite3 "$DB_FILE" "SELECT COUNT(*) FROM \"$TABLE_NAME\";")
    echo ""
    echo "总行数: $COUNT"
fi
