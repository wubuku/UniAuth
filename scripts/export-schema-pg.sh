#!/bin/bash

# 数据库 Schema 导出脚本
# 用于从实际运行的 PostgreSQL 数据库中导出当前项目使用的所有表的 schema
# 导出可执行的 SQL 语句 (CREATE TABLE, CREATE INDEX, etc.)

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONFIG_FILE="$SCRIPT_DIR/../src/main/resources/application-test.yml"

# 检查 psql 是否安装
if ! command -v psql &> /dev/null; then
    echo "❌ psql 未安装，请安装 PostgreSQL 客户端后再试"
    echo "macOS: brew install postgresql"
    echo "Ubuntu: sudo apt install postgresql-client"
    exit 1
fi

# 检查 pg_dump 是否安装
if ! command -v pg_dump &> /dev/null; then
    echo "❌ pg_dump 未安装，请安装 PostgreSQL 客户端后再试"
    echo "macOS: brew install postgresql"
    echo "Ubuntu: sudo apt install postgresql-client"
    exit 1
fi

# 从配置文件读取数据库连接信息
if [ ! -f "$CONFIG_FILE" ]; then
    echo "❌ 配置文件不存在: $CONFIG_FILE"
    exit 1
fi

# 解析YAML配置文件
DB_HOST=$(grep "jdbc:postgresql://" "$CONFIG_FILE" | sed 's/.*jdbc:postgresql:\/\/\${POSTGRES_HOST:\([^}]*\)}.*/\1/')
DB_PORT=$(grep "jdbc:postgresql://" "$CONFIG_FILE" | sed 's/.*:\${POSTGRES_PORT:\([^}]*\)}\/.*/\1/')
DB_NAME=$(grep "jdbc:postgresql://" "$CONFIG_FILE" | sed 's/.*\/\${POSTGRES_DATABASE:\([^}]*\)}.*/\1/')
DB_USER=$(grep -E "^\s+username:" "$CONFIG_FILE" | sed 's/.*\${POSTGRES_USER:\([^}]*\)}.*/\1/')
DB_PASS=$(grep -E "^\s+password:" "$CONFIG_FILE" | sed 's/.*\${POSTGRES_PASSWORD:\([^}]*\)}.*/\1/')

DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5432}"
DB_NAME="${DB_NAME:-blacksheep_dev}"
DB_USER="${DB_USER:-postgres}"
DB_PASS="${DB_PASS:-123456}"

# 环境变量优先
DB_HOST="${POSTGRES_HOST:-$DB_HOST}"
DB_PORT="${POSTGRES_PORT:-$DB_PORT}"
DB_NAME="${POSTGRES_DATABASE:-$DB_NAME}"
DB_USER="${POSTGRES_USER:-$DB_USER}"
DB_PASS="${POSTGRES_PASSWORD:-$DB_PASS}"

# 项目使用的表
PROJECT_TABLES=(
    "users"
    "user_login_methods"
    "web3_nonces"
    "user_authorities"
    "token_blacklist"
    "spring_session"
    "spring_session_attributes"
)

# 导出文件目录
EXPORT_DIR="${1:-$SCRIPT_DIR}"

# 生成带时间戳的文件名
TIMESTAMP=$(date +"%Y%m%d-%H%M%S")
OUTPUT_FILE="${EXPORT_DIR}/schema-${DB_NAME}-${TIMESTAMP}.sql"

# 创建导出目录
mkdir -p "${EXPORT_DIR}"

echo "================================================"
echo "PostgreSQL Schema 导出工具"
echo "================================================"
echo "数据库: ${DB_HOST}:${DB_PORT}/${DB_NAME}"
echo "用户: ${DB_USER}"
echo "导出时间: ${TIMESTAMP}"
echo "输出文件: ${OUTPUT_FILE}"
echo "================================================"
echo ""

# 设置密码环境变量
export PGPASSWORD="${DB_PASS}"

# 测试数据库连接
echo "🔗 测试数据库连接..."
if ! pg_isready -h "${DB_HOST}" -p "${DB_PORT}" -U "${DB_USER}" > /dev/null 2>&1; then
    echo "❌ 无法连接到数据库: ${DB_HOST}:${DB_PORT}"
    echo "请检查:"
    echo "  1. PostgreSQL 服务是否正在运行"
    echo "  2. 数据库配置是否正确 (application-test.yml)"
    exit 1
fi
echo "✅ 数据库连接成功"
echo ""

# 检查项目使用的表是否存在
echo "📋 检查项目使用的表..."
TABLE_COUNT=0
VALID_TABLES=()
for table in "${PROJECT_TABLES[@]}"; do
    EXISTS=$(psql -h "${DB_HOST}" -p "${DB_PORT}" -U "${DB_USER}" -d "${DB_NAME}" -t -A -c "SELECT 1 FROM information_schema.tables WHERE table_schema='public' AND table_name='${table}';" 2>/dev/null)
    if [ "${EXISTS}" = "1" ]; then
        echo "   ✓ ${table}"
        VALID_TABLES+=("$table")
        ((TABLE_COUNT++))
    else
        echo "   ✗ ${table} (不存在)"
    fi
done
echo ""
echo "将导出 ${TABLE_COUNT} 个项目表"
echo ""

if [ "${TABLE_COUNT}" -eq 0 ]; then
    echo "⚠️ 没有找到任何项目表，退出"
    exit 0
fi

# 生成SQL文件
{
    echo "-- ====================================================="
    echo "-- UniAuth 数据库表结构导出"
    echo "-- 导出时间: $(date '+%Y-%m-%d %H:%M:%S')"
    echo "-- 数据库: ${DB_NAME}"
    echo "-- ====================================================="
    echo ""
    echo "-- 可执行的 SQL 语句"
    echo ""

    # 导出每个表的DDL
    for table in "${VALID_TABLES[@]}"; do
        echo "-- -------------------------------------------"
        echo "-- 表: ${table}"
        echo "-- -------------------------------------------"
        echo ""

        # 使用 pg_dump 导出单个表的结构，清理不需要的语句
        pg_dump -h "${DB_HOST}" -p "${DB_PORT}" -U "${DB_USER}" -d "${DB_NAME}" \
            -t "public.${table}" \
            -s \
            --no-owner \
            --no-privileges \
            2>/dev/null | sed '/^--/d' | sed '/^\\/d' | sed '/^SET /d' | sed '/^$/d' | sed '/set_config/d' | sed '/default_tablespace/d' | sed '/default_table_access_method/d' | sed '/row_security/d' || true

        echo ""
    done

} > "${OUTPUT_FILE}"

# 验证导出结果
if [ -f "${OUTPUT_FILE}" ] && [ -s "${OUTPUT_FILE}" ]; then
    FILE_SIZE=$(du -h "${OUTPUT_FILE}" | cut -f1)
    LINE_COUNT=$(wc -l < "${OUTPUT_FILE}")
    echo "✅ Schema 导出成功!"
    echo ""
    echo "📁 输出文件: ${OUTPUT_FILE}"
    echo "📦 文件大小: ${FILE_SIZE}"
    echo "📏 行数: ${LINE_COUNT}"
    echo "📋 表数量: ${TABLE_COUNT}"
    echo ""
    echo "💡 提示: 可以使用以下命令查看导出内容:"
    echo "   cat ${OUTPUT_FILE} | head -50"
else
    echo "❌ 导出失败"
    rm -f "${OUTPUT_FILE}"
    exit 1
fi
