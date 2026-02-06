#!/bin/bash

# 注册与忘记密码功能测试脚本
# 测试所有边界场景

# 自动检测脚本所在目录，支持从项目根目录运行
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "${SCRIPT_DIR}")"

BASE_URL="http://localhost:8082"
EMAIL_REGISTERED="registered@test.com"
EMAIL_NOT_REGISTERED="notregistered@test.com"
EMAIL_EXISTS="exists@test.com"

echo "=============================================="
echo "  注册与忘记密码功能测试"
echo "=============================================="
echo ""

# 等待后端启动
echo "等待后端服务启动..."
for i in {1..30}; do
  if curl -s "${BASE_URL}/api/auth/check-user?username=test" > /dev/null 2>&1; then
    echo "✅ 后端服务已启动"
    break
  fi
  if [ $i -eq 30 ]; then
    echo "❌ 后端服务启动失败"
    exit 1
  fi
  sleep 1
done

echo ""
echo "=============================================="
echo "场景1: 使用已注册邮箱尝试注册"
echo "=============================================="
echo ""

# 先创建一个测试用户
echo "Step 1: 创建测试用户..."
CREATE_RESPONSE=$(curl -s -X POST "${BASE_URL}/api/auth/create-test-user?username=${EMAIL_EXISTS}&password=Test123456")
echo "创建用户响应: ${CREATE_RESPONSE}"

echo ""
echo "Step 2: 使用已注册邮箱尝试注册..."
REGISTER_RESPONSE=$(curl -s -X POST "${BASE_URL}/api/auth/register" \
  -H "Content-Type: application/json" \
  -d "{
    \"username\": \"${EMAIL_EXISTS}\",
    \"email\": \"${EMAIL_EXISTS}\",
    \"password\": \"Test123456\",
    \"displayName\": \"Test User\"
  }")

echo "注册响应: ${REGISTER_RESPONSE}"
ERROR_CODE=$(echo "${REGISTER_RESPONSE}" | grep -o '"errorCode":"[^"]*"' | sed 's/"errorCode":"//;s/"//' || true)
MESSAGE=$(echo "${REGISTER_RESPONSE}" | grep -o '"message":"[^"]*"' | sed 's/"message":"//;s/"//' || true)

if [ "${ERROR_CODE}" = "EMAIL_EXISTS" ] || [ "${ERROR_CODE}" = "EMAIL_ALREADY_REGISTERED" ]; then
  echo "✅ 测试通过: 系统正确拒绝了已注册邮箱"
else
  echo "❌ 测试失败: 期望 EMAIL_EXISTS 错误码，实际收到 ${ERROR_CODE}"
fi

echo ""
echo "=============================================="
echo "场景2: 使用未注册邮箱尝试注册"
echo "=============================================="
echo ""

echo "Step: 使用未注册邮箱注册..."
REGISTER_RESPONSE=$(curl -s -X POST "${BASE_URL}/api/auth/register" \
  -H "Content-Type: application/json" \
  -d "{
    \"username\": \"${EMAIL_NOT_REGISTERED}\",
    \"email\": \"${EMAIL_NOT_REGISTERED}\",
    \"password\": \"Test123456\",
    \"displayName\": \"New User\"
  }")

echo "注册响应: ${REGISTER_RESPONSE}"
REQUIRE_VERIFICATION=$(echo "${REGISTER_RESPONSE}" | grep -o '"requireEmailVerification":[^,}]*' | grep -o 'true\|false' || true)

if [ "${REQUIRE_VERIFICATION}" = "true" ]; then
  echo "✅ 测试通过: 未注册邮箱触发了邮件验证流程"
else
  echo "⚠️ 测试结果: ${REGISTER_RESPONSE}"
fi

echo ""
echo "=============================================="
echo "场景3: 使用已注册邮箱尝试重置密码"
echo "=============================================="
echo ""

echo "Step: 使用已注册邮箱请求密码重置..."
FORGOT_RESPONSE=$(curl -s -X POST "${BASE_URL}/api/auth/forgot-password" \
  -H "Content-Type: application/json" \
  -d "{\"email\": \"${EMAIL_EXISTS}\"}")

echo "忘记密码响应: ${FORGOT_RESPONSE}"
SUCCESS=$(echo "${FORGOT_RESPONSE}" | grep -o '"success":[^,}]*' | grep -o 'true\|false' || true)

if [ "${SUCCESS}" = "true" ]; then
  echo "✅ 测试通过: 已注册邮箱可以请求密码重置"
else
  echo "❌ 测试失败: 已注册邮箱无法请求密码重置"
fi

echo ""
echo "=============================================="
echo "场景4: 使用未注册邮箱尝试重置密码"
echo "=============================================="
echo ""

echo "Step: 使用未注册邮箱请求密码重置..."
FORGOT_RESPONSE=$(curl -s -X POST "${BASE_URL}/api/auth/forgot-password" \
  -H "Content-Type: application/json" \
  -d "{\"email\": \"${EMAIL_NOT_REGISTERED}\"}")

echo "忘记密码响应: ${FORGOT_RESPONSE}"
SUCCESS=$(echo "${FORGOT_RESPONSE}" | grep -o '"success":[^,}]*' | grep -o 'true\|false' || true)
ERROR_CODE=$(echo "${FORGOT_RESPONSE}" | grep -o '"errorCode":"[^"]*"' | sed 's/"errorCode":"//;s/"//' || true)
MESSAGE=$(echo "${FORGOT_RESPONSE}" | grep -o '"message":"[^"]*"' | sed 's/"message":"//;s/"//' || true)

if [ "${SUCCESS}" = "false" ] && [ "${ERROR_CODE}" = "EMAIL_NOT_REGISTERED" ]; then
  echo "✅ 测试通过: 系统正确拒绝了未注册邮箱的密码重置请求"
  echo "   错误信息: ${MESSAGE}"
else
  echo "❌ 测试失败: 期望 success=false, errorCode=EMAIL_NOT_REGISTERED"
  echo "   实际: success=${SUCCESS}, errorCode=${ERROR_CODE}"
fi

echo ""
echo "=============================================="
echo "场景5: 边界情况测试"
echo "=============================================="
echo ""

echo "Test 5.1: 空邮箱..."
EMPTY_RESPONSE=$(curl -s -X POST "${BASE_URL}/api/auth/forgot-password" \
  -H "Content-Type: application/json" \
  -d '{"email": ""}')
echo "响应: ${EMPTY_RESPONSE}"

echo ""
echo "Test 5.2: 无效邮箱格式..."
INVALID_RESPONSE=$(curl -s -X POST "${BASE_URL}/api/auth/forgot-password" \
  -H "Content-Type: application/json" \
  -d '{"email": "invalid-email"}')
echo "响应: ${INVALID_RESPONSE}"

echo ""
echo "Test 5.3: 多次请求（测试频率限制）..."
for i in {1..3}; do
  RATE_RESPONSE=$(curl -s -X POST "${BASE_URL}/api/auth/forgot-password" \
    -H "Content-Type: application/json" \
    -d "{\"email\": \"${EMAIL_EXISTS}\"}")
  echo "第${i}次请求: $(echo "${RATE_RESPONSE}" | grep -o '"message":"[^"]*"' | sed 's/"message":"//;s/"//' || true)"
done

echo ""
echo "=============================================="
echo "测试完成"
echo "=============================================="
