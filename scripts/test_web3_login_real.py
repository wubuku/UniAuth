#!/usr/bin/env python3
"""
Web3 Wallet Login - Full Success Scenario Test Script
=====================================================

This script performs REAL Web3 wallet login with actual Ethereum signatures.
It tests the complete success flow including:
1. New user registration via Web3 wallet
2. Existing user wallet binding
3. JWT token generation
4. Database verification

Requirements:
    pip install web3==6.15.1 eth-account==0.10.0

Usage:
    python3 test_web3_login_real.py [--server-url URL]

Author: UniAuth Development Team
"""

import argparse
import json
import sys
import time
import secrets
from datetime import datetime
from typing import Optional, Dict, Any, Tuple
from dataclasses import dataclass
from enum import Enum
import requests

from eth_account import Account
from eth_account.messages import encode_defunct


class Colors:
    HEADER = '\033[95m'
    BLUE = '\033[94m'
    CYAN = '\033[96m'
    GREEN = '\033[92m'
    WARNING = '\033[93m'
    FAIL = '\033[91m'
    ENDC = '\033[0m'
    BOLD = '\033[1m'


class TestStatus(Enum):
    PASS = "PASS"
    FAIL = "FAIL"
    SKIP = "SKIP"
    ERROR = "ERROR"


@dataclass
class TestResult:
    name: str
    status: TestStatus
    message: str
    details: Optional[Dict[str, Any]] = None


class RealWeb3LoginTester:
    """
    Real Web3 Wallet Login Testing with Actual Ethereum Signatures
    """
    
    def __init__(self, server_url: str):
        self.server_url = server_url.rstrip('/')
        self.results: list[TestResult] = []
        self.session = requests.Session()
        self.session.headers.update({
            'Content-Type': 'application/json',
            'Accept': 'application/json'
        })
        
        self.test_wallet = Account.create()
        self.test_wallet_address = self.test_wallet.address
        self.test_private_key = self.test_wallet.key.hex()
        
        self.binding_wallet = Account.create()
        self.binding_wallet_address = self.binding_wallet.address
        self.binding_private_key = self.binding_wallet.key.hex()
        
        print(f"\n{Colors.CYAN}🧪 Test Wallets Created:{Colors.ENDC}")
        print(f"   Test Wallet 1: {self.test_wallet_address}")
        print(f"   Test Wallet 2: {self.binding_wallet_address}")
    
    def cleanup_nonces(self):
        """Cleanup old nonces for test wallets before testing"""
        try:
            self.session.delete(
                f"{self.server_url}/api/auth/web3/nonce/{self.test_wallet_address}",
                timeout=5
            )
            self.session.delete(
                f"{self.server_url}/api/auth/web3/nonce/{self.binding_wallet_address}",
                timeout=5
            )
            print(f"   ℹ️  Cleaned up old nonces")
        except Exception:
            pass
    
    def log_result(self, result: TestResult):
        """Log a test result"""
        self.results.append(result)
        
        symbol = {
            TestStatus.PASS: "✅",
            TestStatus.FAIL: "❌",
            TestStatus.ERROR: "⚠️",
        }.get(result.status, "❓")
        
        color = {
            TestStatus.PASS: Colors.GREEN,
            TestStatus.FAIL: Colors.FAIL,
            TestStatus.ERROR: Colors.WARNING,
        }.get(result.status, Colors.ENDC)
        
        print(f"  {symbol} {color}{result.name}{Colors.ENDC}")
        print(f"     {result.message}")
        if result.details:
            for key, value in result.details.items():
                print(f"     {key}: {value}")
    
    def sign_message(self, message: str, private_key: str) -> str:
        """Sign a message with real Ethereum private key"""
        account = Account.from_key(private_key)
        message_encoded = encode_defunct(text=message)
        signed = account.sign_message(message_encoded)
        
        signature = signed.signature.hex()
        if not signature.startswith('0x'):
            signature = '0x' + signature
        
        if len(signature) != 132:
            raise ValueError(f"Invalid signature length: {len(signature)}")
        
        return signature
    
    def test_web3_login_with_new_wallet(self) -> TestResult:
        """
        Test: Complete Web3 Login with New Wallet
        
        Flow:
        1. Get nonce for new wallet
        2. Sign the SIWE message with real private key
        3. Verify signature and login
        """
        print(f"\n{Colors.CYAN}{Colors.BOLD}📝 Test: Web3 Login with New Wallet{Colors.ENDC}")
        
        try:
            wallet_address = self.test_wallet_address
            
            # Step 1: Get nonce
            print(f"   Step 1: Getting nonce for {wallet_address[:10]}...")
            nonce_response = self.session.get(
                f"{self.server_url}/api/auth/web3/nonce/{wallet_address}",
                timeout=10
            )
            
            if nonce_response.status_code != 200:
                return TestResult(
                    name="Web3 Login",
                    status=TestStatus.FAIL,
                    message=f"Failed to get nonce: {nonce_response.status_code}",
                    details={"response": nonce_response.text[:200]}
                )
            
            nonce_data = nonce_response.json()
            nonce = nonce_data.get('nonce')
            message = nonce_data.get('message')
            
            print(f"   Step 2: Signing message with private key...")
            
            # Step 2: Sign message with real key
            signature = self.sign_message(message, self.test_private_key)
            print(f"   Signature: {signature[:30]}...")
            
            # Step 3: Verify and login
            print(f"   Step 3: Verifying signature and logging in...")
            login_response = self.session.post(
                f"{self.server_url}/api/auth/web3/verify",
                json={
                    "walletAddress": wallet_address,
                    "message": message,
                    "signature": signature,
                    "nonce": nonce
                },
                timeout=10
            )
            
            if login_response.status_code != 200:
                return TestResult(
                    name="Web3 Login",
                    status=TestStatus.FAIL,
                    message=f"Login failed: {login_response.status_code}",
                    details={"response": login_response.text}
                )
            
            login_data = login_response.json()
            access_token = login_data.get('accessToken')
            user_id = login_data.get('userId')
            is_new_user = login_data.get('isNewUser')
            
            print(f"   ✅ Login successful!")
            print(f"   User ID: {user_id}")
            print(f"   Is New User: {is_new_user}")
            print(f"   Token: {access_token[:30]}...")
            
            return TestResult(
                name="Web3 Login",
                status=TestStatus.PASS,
                message="Successfully logged in via Web3 wallet",
                details={
                    "user_id": user_id,
                    "wallet_address": wallet_address,
                    "is_new_user": is_new_user,
                    "token_received": bool(access_token)
                }
            )
            
        except Exception as e:
            return TestResult(
                name="Web3 Login",
                status=TestStatus.ERROR,
                message=f"Exception: {str(e)}"
            )
    
    def test_bind_web3_to_local_user(self) -> TestResult:
        """
        Test: Bind Web3 Wallet to New Local User
        
        Flow:
        1. Register a new local user (username/password)
        2. Login with local credentials to get token
        3. Bind binding_wallet to this user
        """
        print(f"\n{Colors.CYAN}{Colors.BOLD}🔗 Test: Bind Web3 Wallet to Local User{Colors.ENDC}")
        
        try:
            # Step 1: Register a new local user with unique username
            local_username = f"localuser_{secrets.token_hex(8)}"
            local_email = f"{local_username}@test.local"
            local_password = "Password123!"
            
            print(f"   Step 1: Registering new local user: {local_username}")
            
            register_response = self.session.post(
                f"{self.server_url}/api/auth/register",
                json={
                    "username": local_username,
                    "email": local_email,
                    "password": local_password,
                    "displayName": f"Local Test User"
                },
                timeout=10
            )
            
            if register_response.status_code not in [200, 201]:
                return TestResult(
                    name="Web3 Binding",
                    status=TestStatus.FAIL,
                    message=f"Failed to register local user: {register_response.status_code}",
                    details={"response": register_response.text[:200]}
                )
            
            print(f"   ✅ Local user registered successfully")
            
            # Step 2: Login with local credentials
            print(f"   Step 2: Logging in with local credentials...")
            
            login_response = self.session.post(
                f"{self.server_url}/api/auth/login",
                params={
                    "username": local_username,
                    "password": local_password
                },
                timeout=10
            )
            
            if login_response.status_code != 200:
                return TestResult(
                    name="Web3 Binding",
                    status=TestStatus.FAIL,
                    message=f"Failed to login with local credentials: {login_response.status_code}",
                    details={"response": login_response.text[:200]}
                )
            
            login_data = login_response.json()
            access_token = login_data.get('accessToken')
            user_id = login_data.get('userId')
            
            print(f"   ✅ Local login successful, user ID: {user_id}")
            
            # Step 3: Bind binding_wallet to this user
            print(f"   Step 3: Binding {self.binding_wallet_address[:10]}... to user")
            
            nonce_response = self.session.get(
                f"{self.server_url}/api/auth/web3/nonce/{self.binding_wallet_address}",
                timeout=10
            )
            
            if nonce_response.status_code != 200:
                return TestResult(
                    name="Web3 Binding",
                    status=TestStatus.FAIL,
                    message="Failed to get nonce for binding wallet"
                )
            
            nonce_data = nonce_response.json()
            signature = self.sign_message(
                nonce_data.get('message'),
                self.binding_private_key
            )
            
            # Use bind endpoint with auth token
            bind_response = self.session.post(
                f"{self.server_url}/api/auth/web3/bind",
                headers={
                    'Authorization': f'Bearer {access_token}',
                    'Content-Type': 'application/json'
                },
                json={
                    "walletAddress": self.binding_wallet_address,
                    "message": nonce_data.get('message'),
                    "signature": signature,
                    "nonce": nonce_data.get('nonce')
                },
                timeout=10
            )
            
            if bind_response.status_code != 200:
                return TestResult(
                    name="Web3 Binding",
                    status=TestStatus.FAIL,
                    message=f"Binding failed: {bind_response.status_code}",
                    details={"response": bind_response.text[:200]}
                )
            
            print(f"   ✅ Wallet bound successfully!")
            
            return TestResult(
                name="Web3 Binding",
                status=TestStatus.PASS,
                message="Successfully bound Web3 wallet to new local user",
                details={
                    "user_id": user_id,
                    "bound_wallet": self.binding_wallet_address
                }
            )
            
        except Exception as e:
            return TestResult(
                name="Web3 Binding",
                status=TestStatus.ERROR,
                message=f"Exception: {str(e)}"
            )
    
    def test_repeated_web3_login(self) -> TestResult:
        """
        Test: Login Again with Already Registered Wallet
        
        This test verifies that logging in with a wallet that already exists
        returns isNewUser=false, proving the system recognizes returning users.
        """
        print(f"\n{Colors.CYAN}{Colors.BOLD}🔄 Test: Login with Existing Wallet{Colors.ENDC}")
        
        try:
            wallet_address = self.test_wallet_address
            
            # Get nonce for the already registered wallet
            print(f"   Getting nonce for {wallet_address[:10]}... (existing wallet)")
            
            nonce_response = self.session.get(
                f"{self.server_url}/api/auth/web3/nonce/{wallet_address}",
                timeout=10
            )
            
            if nonce_response.status_code != 200:
                return TestResult(
                    name="Existing Wallet Login",
                    status=TestStatus.FAIL,
                    message=f"Failed to get nonce: {nonce_response.status_code}"
                )
            
            nonce_data = nonce_response.json()
            signature = self.sign_message(
                nonce_data.get('message'),
                self.test_private_key
            )
            
            # Login
            login_response = self.session.post(
                f"{self.server_url}/api/auth/web3/verify",
                json={
                    "walletAddress": wallet_address,
                    "message": nonce_data.get('message'),
                    "signature": signature,
                    "nonce": nonce_data.get('nonce')
                },
                timeout=10
            )
            
            if login_response.status_code != 200:
                return TestResult(
                    name="Existing Wallet Login",
                    status=TestStatus.FAIL,
                    message=f"Login failed: {login_response.status_code}"
                )
            
            login_data = login_response.json()
            is_new_user = login_data.get('isNewUser')
            user_id = login_data.get('userId')
            
            print(f"   ℹ️  isNewUser: {is_new_user}")
            print(f"   ℹ️  User ID: {user_id}")
            print(f"   ✅ Login successful!")
            
            # Verify it's recognized as existing user
            if is_new_user:
                return TestResult(
                    name="Existing Wallet Login",
                    status=TestStatus.FAIL,
                    message="Expected isNewUser=false for existing wallet"
                )
            
            return TestResult(
                name="Existing Wallet Login",
                status=TestStatus.PASS,
                message="Correctly identified returning user (isNewUser=false)",
                details={"isNewUser": is_new_user, "user_id": user_id}
            )
            
        except Exception as e:
            return TestResult(
                name="Existing Wallet Login",
                status=TestStatus.ERROR,
                message=f"Exception: {str(e)}"
            )
    
    def test_wallet_status_check(self) -> TestResult:
        """
        Test: Verify Both Wallets in Database
        """
        print(f"\n{Colors.CYAN}{Colors.BOLD}🗄️  Test: Verify Wallets in Database{Colors.ENDC}")
        
        try:
            # Check wallet 1
            status1_response = self.session.get(
                f"{self.server_url}/api/auth/web3/status/{self.test_wallet_address}",
                timeout=10
            )
            
            if status1_response.status_code != 200:
                return TestResult(
                    name="Database Verification",
                    status=TestStatus.FAIL,
                    message=f"Wallet 1 status check failed: {status1_response.status_code}"
                )
            
            status1 = status1_response.json()
            
            # Check wallet 2
            status2_response = self.session.get(
                f"{self.server_url}/api/auth/web3/status/{self.binding_wallet_address}",
                timeout=10
            )
            
            if status2_response.status_code != 200:
                return TestResult(
                    name="Database Verification",
                    status=TestStatus.FAIL,
                    message=f"Wallet 2 status check failed: {status2_response.status_code}"
                )
            
            status2 = status2_response.json()
            
            wallet1_bound = status1.get('isBound')
            wallet2_bound = status2.get('isBound')
            
            print(f"   Wallet 1 ({self.test_wallet_address[:10]}...): isBound={wallet1_bound}")
            print(f"   Wallet 2 ({self.binding_wallet_address[:10]}...): isBound={wallet2_bound}")
            
            if not wallet1_bound or not wallet2_bound:
                return TestResult(
                    name="Database Verification",
                    status=TestStatus.FAIL,
                    message="Wallets not marked as bound"
                )
            
            return TestResult(
                name="Database Verification",
                status=TestStatus.PASS,
                message="Both wallets verified in database",
                details={
                    "wallet1_bound": wallet1_bound,
                    "wallet2_bound": wallet2_bound
                }
            )
            
        except Exception as e:
            return TestResult(
                name="Database Verification",
                status=TestStatus.ERROR,
                message=f"Exception: {str(e)}"
            )
    
    def run_all_tests(self) -> bool:
        """Execute all tests"""
        print(f"\n{Colors.HEADER}{Colors.BOLD}")
        print("╔════════════════════════════════════════════════════════════════╗")
        print("║    Web3 Wallet Login - REAL Success Scenario Tests           ║")
        print("╚════════════════════════════════════════════════════════════════╝")
        print(f"{Colors.ENDC}")
        
        print(f"\n{Colors.BLUE}ℹ️  Cleaning up old test data...{Colors.ENDC}")
        self.cleanup_nonces()
        
        tests = [
            ("Web3 Login", self.test_web3_login_with_new_wallet),
            ("Web3 Binding", self.test_bind_web3_to_local_user),
            ("Repeated Login", self.test_repeated_web3_login),
            ("Database Verification", self.test_wallet_status_check),
        ]
        
        for test_name, test_method in tests:
            result = test_method()
            self.log_result(result)
        
        return self.generate_report()
    
    def generate_report(self) -> bool:
        """Generate test report"""
        passed = sum(1 for r in self.results if r.status == TestStatus.PASS)
        failed = sum(1 for r in self.results if r.status == TestStatus.FAIL)
        total = len(self.results)
        
        print(f"\n{Colors.BLUE}{'='*60}{Colors.ENDC}")
        print(f"\n{Colors.HEADER}{Colors.BOLD}📊 Test Report{Colors.ENDC}")
        print(f"\n   Total: {total}")
        print(f"   {Colors.GREEN}Passed: {passed}{Colors.ENDC}")
        print(f"   {Colors.FAIL}Failed: {failed}{Colors.ENDC}")
        
        overall_pass = failed == 0
        status = f"\n{Colors.GREEN}🎉 ALL TESTS PASSED{Colors.ENDC}" if overall_pass else f"\n{Colors.FAIL}❌ SOME TESTS FAILED{Colors.ENDC}"
        print(status)
        
        return overall_pass


def main():
    """Main entry point"""
    parser = argparse.ArgumentParser(
        description='Web3 Wallet Login - Real Success Scenario Test Script'
    )
    parser.add_argument(
        '--server-url',
        default='http://localhost:8081',
        help='Server URL (default: http://localhost:8081)'
    )
    
    args = parser.parse_args()
    
    print(f"\n{Colors.BLUE}{'='*60}{Colors.ENDC}")
    print(f"{Colors.BLUE}Starting Web3 Wallet Login Tests{Colors.ENDC}")
    print(f"{Colors.BLUE}Server URL: {args.server_url}{Colors.ENDC}")
    print(f"{Colors.BLUE}{'='*60}{Colors.ENDC}")
    
    tester = RealWeb3LoginTester(args.server_url)
    success = tester.run_all_tests()
    
    sys.exit(0 if success else 1)


if __name__ == '__main__':
    main()
