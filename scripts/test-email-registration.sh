#!/bin/bash

# 邮箱注册流程测试脚本
# 功能：测试邮箱注册 + 验证码验证 + 密码重置完整流程
# 特点：自动从数据库查询验证码，无需用户手动输入

# 自动检测脚本所在目录，支持从项目根目录运行
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "${SCRIPT_DIR}")"

set -e

BASE_URL="http://localhost:8082"
EMAIL="xxx@example.com"
PASSWORD="TestPassword123!"
NEW_PASSWORD="NewPassword456!"
DISPLAY_NAME="测试用户"

# 数据库配置
DB_HOST="localhost"
DB_PORT="5432"
DB_NAME="blacksheep_dev"
DB_USER="postgres"
DB_PASSWORD="123456"

# 函数：从数据库查询验证码
get_verification_code() {
    local purpose="$1"
    PGPASSWORD="${DB_PASSWORD}" psql -h "${DB_HOST}" -p "${DB_PORT}" -U "${DB_USER}" -d "${DB_NAME}" -t -c "SELECT verification_code FROM email_verification_codes WHERE email = '${EMAIL}' AND purpose = '${purpose}' AND is_used = false ORDER BY created_at DESC LIMIT 1;" 2>/dev/null | tr -d ' '
}

echo "=============================================="
echo "  邮箱注册流程测试脚本 (自动查询验证码)"
echo "=============================================="
echo ""
echo "测试邮箱: $EMAIL"
echo "密码: $PASSWORD"
echo "显示名称: $DISPLAY_NAME"
echo ""

# Step 1: 先删除可能存在的旧用户数据（清理）
echo "=============================================="
echo "Step 0: 清理旧数据（如有）..."
echo "=============================================="
curl -s -X DELETE "${BASE_URL}/api/test/users/${EMAIL}" > /dev/null 2>&1 || true
echo "清理完成"
echo ""

# Step 2: 调用注册接口
echo "=============================================="
echo "Step 1: 调用注册接口..."
echo "=============================================="
echo ""

REGISTER_RESPONSE=$(curl -s -X POST "${BASE_URL}/api/auth/register" \
  -H "Content-Type: application/json" \
  -d "{
    \"username\": \"${EMAIL}\",
    \"email\": \"${EMAIL}\",
    \"password\": \"${PASSWORD}\",
    \"displayName\": \"${DISPLAY_NAME}\"
  }")

echo "注册响应: ${REGISTER_RESPONSE}"
echo ""

# 解析响应
REQUIRE_EMAIL_VERIFICATION=$(echo "${REGISTER_RESPONSE}" | grep -o '"requireEmailVerification":[^,}]*' | grep -o 'true\|false' || true)
USERNAME_FROM_RESPONSE=$(echo "${REGISTER_RESPONSE}" | grep -o '"username":"[^"]*"' | head -1 | sed 's/"username":"//;s/"//')
ERROR_CODE=$(echo "${REGISTER_RESPONSE}" | grep -o '"error":"[^"]*"' | sed 's/"error":"//;s/"//' || true)

if [ "${ERROR_CODE}" = "USERNAME_EXISTS" ]; then
  echo "❌ 用户已存在，无需重新注册"
  echo "   用户名: ${USERNAME_FROM_RESPONSE}"
  echo ""
  echo "=============================================="
  echo "  用户已存在 - 测试密码重置"
  echo "=============================================="
  echo ""
  # 直接执行密码重置测试
  echo "=============================================="
  echo "Step 6: 请求密码重置验证码..."
  echo "=============================================="
  
  FORGOT_RESPONSE=$(curl -s -X POST "${BASE_URL}/api/auth/forgot-password" \
    -H "Content-Type: application/json" \
    -d "{\"email\": \"${EMAIL}\"}")
  
  echo "请求密码重置响应: ${FORGOT_RESPONSE}"
  FORGOT_SUCCESS=$(echo "${FORGOT_RESPONSE}" | grep -o '"success":[^,}]*' | grep -o 'true\|false' || true)
  
  if [ "${FORGOT_SUCCESS}" = "true" ]; then
    echo "✅ 验证码已发送"
    echo ""
    echo "=============================================="
    echo "Step 7: 从数据库查询验证码..."
    echo "=============================================="
    sleep 1
    RESET_CODE=$(get_verification_code "PASSWORD_RESET")
    if [ -n "${RESET_CODE}" ]; then
      echo "✅ 获取验证码: ${RESET_CODE}"
      echo ""
      echo "=============================================="
      echo "Step 8: 验证验证码并重置密码..."
      echo "=============================================="
      RESET_RESPONSE=$(curl -s -X POST "${BASE_URL}/api/auth/verify-reset-code" \
        -H "Content-Type: application/json" \
        -d "{\"email\": \"${EMAIL}\",\"verificationCode\": \"${RESET_CODE}\",\"newPassword\": \"${NEW_PASSWORD}\"}")
      echo "密码重置响应: ${RESET_RESPONSE}"
      RESET_SUCCESS=$(echo "${RESET_RESPONSE}" | grep -o '"success":[^,}]*' | grep -o 'true\|false' || true)
      if [ "${RESET_SUCCESS}" = "true" ]; then
        echo "✅ 密码重置成功"
      fi
    fi
  fi
  echo ""
  echo "测试脚本执行完毕"
  exit 0
fi

if [ "${REQUIRE_EMAIL_VERIFICATION}" = "true" ]; then
  echo "✅ 需要邮箱验证"
  echo "   用户名: ${USERNAME_FROM_RESPONSE}"
  echo ""

  # Step 3: 发送验证码
  echo "=============================================="
  echo "Step 2: 发送验证码到邮箱..."
  echo "=============================================="
  echo ""

  SEND_CODE_RESPONSE=$(curl -s -X POST "${BASE_URL}/api/auth/send-verification-code" \
    -H "Content-Type: application/json" \
    -d "{
      \"email\": \"${EMAIL}\",
      \"purpose\": \"REGISTRATION\",
      \"password\": \"${PASSWORD}\",
      \"displayName\": \"${DISPLAY_NAME}\"
    }")

  echo "发送验证码响应: ${SEND_CODE_RESPONSE}"
  echo ""

  SEND_SUCCESS=$(echo "${SEND_CODE_RESPONSE}" | grep -o '"success":[^,}]*' | grep -o 'true\|false')

  if [ "${SEND_SUCCESS}" = "true" ]; then
    echo "✅ 验证码已发送成功"
    echo ""

    # Step 4: 从数据库查询验证码（调用函数）
    echo "=============================================="
    echo "Step 3: 从数据库查询验证码..."
    echo "=============================================="
    echo ""

    # 等待1秒确保数据已写入数据库
    sleep 1

    # 调用函数查询验证码
    VERIFICATION_CODE=$(get_verification_code "REGISTRATION")

    if [ -z "${VERIFICATION_CODE}" ]; then
      echo "❌ 未在数据库中找到验证码"
      echo "请检查数据库连接或验证码是否已过期"
      exit 1
    fi

    echo "✅ 已从数据库获取验证码: ${VERIFICATION_CODE}"
    echo ""

    # Step 5: 验证验证码
    echo "=============================================="
    echo "Step 4: 验证验证码..."
    echo "=============================================="
    echo ""

    VERIFY_RESPONSE=$(curl -s -X POST "${BASE_URL}/api/auth/verify-email" \
      -H "Content-Type: application/json" \
      -d "{
        \"email\": \"${EMAIL}\",
        \"verificationCode\": \"${VERIFICATION_CODE}\"
      }")

    echo "验证响应: ${VERIFY_RESPONSE}"
    echo ""

    # 解析验证结果
    VERIFY_SUCCESS=$(echo "${VERIFY_RESPONSE}" | grep -o '"success":[^,}]*' | grep -o 'true\|false')

    if [ "${VERIFY_SUCCESS}" = "true" ]; then
      echo "✅ 邮箱验证成功！"
      echo ""

      # 提取令牌信息
      ACCESS_TOKEN=$(echo "${VERIFY_RESPONSE}" | grep -o '"accessToken":"[^"]*"' | sed 's/"accessToken":"//;s/"//')
      REFRESH_TOKEN=$(echo "${VERIFY_RESPONSE}" | grep -o '"refreshToken":"[^"]*"' | sed 's/"refreshToken":"//;s/"//')

      if [ -n "${ACCESS_TOKEN}" ]; then
        echo "✅ 已获取访问令牌"
        echo ""
        echo "访问令牌 (前50字符): ${ACCESS_TOKEN:0:50}..."
        echo "刷新令牌 (前50字符): ${REFRESH_TOKEN:0:50}..."
        echo ""

        # Step 6: 使用令牌获取用户信息
        echo "=============================================="
        echo "Step 5: 使用令牌获取用户信息..."
        echo "=============================================="
        echo ""

        # 注意：用户接口是 /user，不是 /users/me
        USER_INFO=$(curl -s -X GET "${BASE_URL}/api/user" \
          -H "Authorization: Bearer ${ACCESS_TOKEN}")

        echo "用户信息: ${USER_INFO}"
        echo ""

        USER_ID=$(echo "${USER_INFO}" | grep -o '"id":"[^"]*"' | sed 's/"id":"//;s/"//')
        USER_EMAIL=$(echo "${USER_INFO}" | grep -o '"email":"[^"]*"' | sed 's/"email":"//;s/"//')

        echo ""
        echo "=============================================="
        echo "  🎉 注册成功！"
        echo "=============================================="
        echo ""
        echo "用户ID: ${USER_ID}"
        echo "用户邮箱: ${USER_EMAIL}"
        echo "用户名: ${EMAIL}"
        echo ""

        # Step 7: 测试登录
        echo "=============================================="
        echo "Step 6: 测试使用邮箱登录..."
        echo "=============================================="
        echo ""

        # 注意：登录接口使用 query params (@RequestParam)，不是 JSON body
        LOGIN_RESPONSE=$(curl -s -X POST "${BASE_URL}/api/auth/login?username=${EMAIL}&password=${PASSWORD}")

        echo "登录响应: ${LOGIN_RESPONSE}"
        echo ""

        LOGIN_SUCCESS=$(echo "${LOGIN_RESPONSE}" | grep -o '"success":[^,}]*' | grep -o 'true\|false')

        if [ "${LOGIN_SUCCESS}" = "true" ]; then
          echo "✅ 登录成功！"
        else
          echo "⚠️ 登录响应已收到，请检查"
        fi
        
        # =============================================
        # 追加：密码重置测试
        # =============================================
        echo ""
        echo "╔════════════════════════════════════════════════╗"
        echo "║         追加测试：密码重置                  ║"
        echo "╚════════════════════════════════════════════════╝"
        echo ""
        
        # Step 8: 请求密码重置验证码
        echo "=============================================="
        echo "Step 7: 请求密码重置验证码..."
        echo "=============================================="
        
        FORGOT_RESPONSE=$(curl -s -X POST "${BASE_URL}/api/auth/forgot-password" \
          -H "Content-Type: application/json" \
          -d "{\"email\": \"${EMAIL}\"}")
        
        echo "请求密码重置响应: ${FORGOT_RESPONSE}"
        FORGOT_SUCCESS=$(echo "${FORGOT_RESPONSE}" | grep -o '"success":[^,}]*' | grep -o 'true\|false')
        
        if [ "${FORGOT_SUCCESS}" = "true" ]; then
          echo "✅ 验证码已发送"
          echo ""
          
          # Step 9: 查询密码重置验证码（调用函数）
          echo "=============================================="
          echo "Step 8: 从数据库查询验证码..."
          echo "=============================================="
          sleep 1
          
          # 调用函数查询验证码
          RESET_CODE=$(get_verification_code "PASSWORD_RESET")
          
          if [ -n "${RESET_CODE}" ]; then
            echo "✅ 获取验证码: ${RESET_CODE}"
            echo ""
            
            # Step 10: 重置密码
            echo "=============================================="
            echo "Step 9: 验证验证码并重置密码..."
            echo "=============================================="
            
            RESET_RESPONSE=$(curl -s -X POST "${BASE_URL}/api/auth/verify-reset-code" \
              -H "Content-Type: application/json" \
              -d "{
                \"email\": \"${EMAIL}\",
                \"verificationCode\": \"${RESET_CODE}\",
                \"newPassword\": \"${NEW_PASSWORD}\"
              }")
            
            echo "密码重置响应: ${RESET_RESPONSE}"
            RESET_SUCCESS=$(echo "${RESET_RESPONSE}" | grep -o '"success":[^,}]*' | grep -o 'true\|false')
            
            if [ "${RESET_SUCCESS}" = "true" ]; then
              echo "✅ 密码重置成功"
              echo ""
              
              # Step 11: 测试新密码登录
              echo "=============================================="
              echo "Step 10: 测试新密码登录..."
              echo "=============================================="
              
              NEW_LOGIN_RESPONSE=$(curl -s -X POST "${BASE_URL}/api/auth/login?username=${EMAIL}&password=${NEW_PASSWORD}")
              echo "新密码登录响应: ${NEW_LOGIN_RESPONSE}"
              NEW_LOGIN_SUCCESS=$(echo "${NEW_LOGIN_RESPONSE}" | grep -o '"success":[^,}]*' | grep -o 'true\|false')
              
              if [ "${NEW_LOGIN_SUCCESS}" = "true" ]; then
                echo "✅ 新密码登录成功"
              else
                echo "❌ 新密码登录失败"
              fi
            else
              echo "❌ 密码重置失败"
            fi
          else
            echo "❌ 未找到验证码"
          fi
        else
          echo "❌ 发送验证码失败"
        fi
      else
        echo "⚠️ 未获取到令牌，但验证响应显示成功"
        echo "请检查后端日志获取详细信息"
      fi
    else
      echo "❌ 验证码验证失败"
      ERROR_MSG=$(echo "${VERIFY_RESPONSE}" | grep -o '"message":"[^"]*"' | sed 's/"message":"//;s/"//')
      REMAINING=$(echo "${VERIFY_RESPONSE}" | grep -o '"remainingAttempts":[0-9]*' | sed 's/"remainingAttempts"://')

      if [ -n "${ERROR_MSG}" ]; then
        echo "错误信息: ${ERROR_MSG}"
      fi
      if [ -n "${REMAINING}" ]; then
        echo "剩余尝试次数: ${REMAINING}"
      fi
      echo ""
      echo "=============================================="
      echo "  ❌ 注册失败 - 验证码错误"
      echo "=============================================="
    fi
  else
    echo "❌ 发送验证码失败"
    ERROR_MSG=$(echo "${SEND_CODE_RESPONSE}" | grep -o '"error":"[^"]*"' | sed 's/"error":"//;s/"//')
    if [ -n "${ERROR_MSG}" ]; then
      echo "错误信息: ${ERROR_MSG}"
    fi
    echo ""
    echo "=============================================="
    echo "  ❌ 注册失败 - 无法发送验证码"
    echo "=============================================="
  fi
else
  echo "⚠️ 无需邮箱验证，可能是配置问题"
  echo "响应: ${REGISTER_RESPONSE}"
  echo ""
  echo "=============================================="
  echo "  ⚠️ 测试完成 - 无需邮箱验证"
  echo "=============================================="
fi

echo ""
echo "测试脚本执行完毕"
