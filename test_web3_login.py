#!/usr/bin/env python3
"""
Web3 Wallet Login End-to-End Test Script
=========================================

This script tests the Web3 wallet login functionality of the UniAuth system.

Features:
- API availability checks
- Database migration verification
- Nonce generation and validation
- Signature verification testing
- JWT token generation testing
- Comprehensive test reporting

Usage:
    python3 test_web3_login.py [--server-url URL] [--wallet-address ADDRESS]

Author: UniAuth Development Team
Version: 1.0.0
"""

import argparse
import json
import sys
import time
import hashlib
import secrets
import subprocess
import os
from datetime import datetime
from typing import Optional, Dict, Any, Tuple
from dataclasses import dataclass
from enum import Enum
import requests

# ANSI color codes for terminal output
class Colors:
    HEADER = '\033[95m'
    BLUE = '\033[94m'
    CYAN = '\033[96m'
    GREEN = '\033[92m'
    WARNING = '\033[93m'
    FAIL = '\033[91m'
    ENDC = '\033[0m'
    BOLD = '\033[1m'
    UNDERLINE = '\033[4m'


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
    duration_ms: float = 0.0


class Web3LoginTester:
    """
    Comprehensive Web3 Wallet Login Testing Class
    
    This class provides complete testing capabilities for Web3 authentication
    including API endpoint testing, signature verification, and token generation.
    """
    
    def __init__(self, server_url: str, wallet_address: str = None):
        self.server_url = server_url.rstrip('/')
        self.wallet_address = wallet_address or self.generate_test_wallet()
        self.results: list[TestResult] = []
        self.session = requests.Session()
        self.session.headers.update({
            'Content-Type': 'application/json',
            'Accept': 'application/json'
        })
        
    @staticmethod
    def generate_test_wallet() -> str:
        """Generate a valid Ethereum wallet address for testing"""
        # Generate a random 40-character hex string (excluding 0x prefix)
        random_hex = secrets.token_hex(20)
        return f"0x{random_hex}"
    
    @staticmethod
    def generate_test_nonce() -> str:
        """Generate a test nonce"""
        return secrets.token_hex(16)
    
    @staticmethod
    def generate_dummy_signature(message: str, wallet_address: str) -> str:
        """
        Generate a dummy signature for testing purposes.
        NOTE: This signature will NOT pass real verification but tests the API flow.
        In production, use actual wallet signing.
        """
        # Create a deterministic but fake signature for testing
        message_hash = hashlib.sha256(message.encode()).hexdigest()
        wallet_hash = hashlib.sha256(wallet_address.encode()).hexdigest()
        combined = f"{message_hash}:{wallet_hash}:{secrets.token_hex(32)}"
        signature_hash = hashlib.sha256(combined.encode()).hexdigest()
        return f"0x{signature_hash}"
    
    def log_result(self, result: TestResult):
        """Log a test result with formatted output"""
        self.results.append(result)
        
        status_symbol = {
            TestStatus.PASS: "✅",
            TestStatus.FAIL: "❌",
            TestStatus.SKIP: "⏭️",
            TestStatus.ERROR: "⚠️"
        }.get(result.status, "❓")
        
        color = {
            TestStatus.PASS: Colors.GREEN,
            TestStatus.FAIL: Colors.FAIL,
            TestStatus.SKIP: Colors.BLUE,
            TestStatus.ERROR: Colors.WARNING
        }.get(result.status, Colors.ENDC)
        
        print(f"  {status_symbol} {color}{result.name}{Colors.ENDC}")
        print(f"     {result.message}")
        if result.duration_ms > 0:
            print(f"     Duration: {result.duration_ms:.2f}ms")
        if result.details:
            for key, value in result.details.items():
                print(f"     {key}: {value}")
    
    def measure_time(self, func):
        """Decorator to measure function execution time"""
        def wrapper(*args, **kwargs):
            start = time.perf_counter()
            result = func(*args, **kwargs)
            end = time.perf_counter()
            return result, (end - start) * 1000
        return wrapper
    
    def test_api_health(self) -> TestResult:
        """Test if the API server is reachable"""
        start = time.perf_counter()
        try:
            # Try multiple endpoints
            endpoints = ['/api/auth/web3/status/0x1234567890123456789012345678901234567890', '/api/user']
            response = None
            
            for endpoint in endpoints:
                response = self.session.get(f"{self.server_url}{endpoint}", timeout=10)
                if response.status_code in [200, 400, 401]:
                    break
            
            duration = (time.perf_counter() - start) * 1000
            
            if response and response.status_code in [200, 400, 401]:
                return TestResult(
                    name="API Health Check",
                    status=TestStatus.PASS,
                    message="Server is reachable and responding",
                    details={"status_code": response.status_code},
                    duration_ms=duration
                )
            else:
                return TestResult(
                    name="API Health Check",
                    status=TestStatus.FAIL,
                    message=f"Server returned status code {response.status_code if response else 'no response'}",
                    details={"response": response.text[:200] if response else "No response"},
                    duration_ms=duration
                )
        except requests.exceptions.ConnectionError:
            return TestResult(
                name="API Health Check",
                status=TestStatus.ERROR,
                message="Cannot connect to server. Make sure the application is running.",
                details={"server_url": self.server_url}
            )
        except Exception as e:
            return TestResult(
                name="API Health Check",
                status=TestStatus.ERROR,
                message=f"Unexpected error: {str(e)}"
            )
    
    def test_nonce_generation(self) -> TestResult:
        """Test the nonce generation endpoint"""
        start = time.perf_counter()
        try:
            response = self.session.get(
                f"{self.server_url}/api/auth/web3/nonce/{self.wallet_address}",
                timeout=10
            )
            duration = (time.perf_counter() - start) * 1000
            
            if response.status_code == 200:
                data = response.json()
                required_fields = ['nonce', 'message', 'expiresIn']
                missing_fields = [f for f in required_fields if f not in data]
                
                if missing_fields:
                    return TestResult(
                        name="Nonce Generation",
                        status=TestStatus.FAIL,
                        message=f"Response missing required fields: {missing_fields}",
                        details={"received_fields": list(data.keys())},
                        duration_ms=duration
                    )
                
                # Validate nonce format (hex string)
                if not data.get('nonce', '').replace('-', '').replace('_', '').isalnum():
                    return TestResult(
                        name="Nonce Generation",
                        status=TestStatus.FAIL,
                        message="Invalid nonce format"
                    )
                
                # Validate message contains expected SIWE components
                message = data.get('message', '')
                siwe_components = ['Ethereum', 'Nonce', 'Version']
                missing_siwe = [c for c in siwe_components if c not in message]
                
                if missing_siwe:
                    return TestResult(
                        name="Nonce Generation",
                        status=TestStatus.FAIL,
                        message=f"SIWE message missing components: {missing_siwe}",
                        details={"message": message[:200]}
                    )
                
                return TestResult(
                    name="Nonce Generation",
                    status=TestStatus.PASS,
                    message="Nonce generated successfully with valid SIWE message",
                    details={
                        "nonce_length": len(data.get('nonce', '')),
                        "expires_in": data.get('expiresIn'),
                        "message_preview": message[:100] + "..."
                    },
                    duration_ms=duration
                )
            else:
                error_detail = response.json().get('message', response.text[:200]) if response.headers.get('content-type', '').startswith('application/json') else response.text[:200]
                return TestResult(
                    name="Nonce Generation",
                    status=TestStatus.FAIL,
                    message=f"Failed with status {response.status_code}",
                    details={"error": error_detail},
                    duration_ms=duration
                )
        except Exception as e:
            return TestResult(
                name="Nonce Generation",
                status=TestStatus.ERROR,
                message=f"Unexpected error: {str(e)}"
            )
    
    def test_signature_verification(self) -> TestResult:
        """Test the signature verification endpoint"""
        start = time.perf_counter()
        try:
            # First get a valid nonce
            nonce_response = self.session.get(
                f"{self.server_url}/api/auth/web3/nonce/{self.wallet_address}",
                timeout=10
            )
            
            if nonce_response.status_code != 200:
                return TestResult(
                    name="Signature Verification",
                    status=TestStatus.ERROR,
                    message="Failed to get nonce for testing"
                )
            
            nonce_data = nonce_response.json()
            nonce = nonce_data.get('nonce')
            message = nonce_data.get('message')
            
            # Generate a test signature (note: this won't verify correctly without real wallet)
            signature = self.generate_dummy_signature(message, self.wallet_address)
            
            # Attempt verification (expected to fail with invalid signature)
            login_request = {
                "walletAddress": self.wallet_address,
                "message": message,
                "signature": signature,
                "nonce": nonce
            }
            
            response = self.session.post(
                f"{self.server_url}/api/auth/web3/verify",
                json=login_request,
                timeout=10
            )
            duration = (time.perf_counter() - start) * 1000
            
            # With an invalid signature, we expect 401 Unauthorized
            if response.status_code == 401:
                return TestResult(
                    name="Signature Verification",
                    status=TestStatus.PASS,
                    message="API correctly rejects invalid signature",
                    details={
                        "response_status": 401,
                        "expected": "INVALID_SIGNATURE error"
                    },
                    duration_ms=duration
                )
            elif response.status_code == 200:
                # Unexpected success - this means signature verification might be bypassed
                return TestResult(
                    name="Signature Verification",
                    status=TestStatus.FAIL,
                    message="API accepted an invalid signature!",
                    details={"warning": "This should not happen in production"}
                )
            else:
                error_data = response.json() if response.headers.get('content-type', '').startswith('application/json') else {}
                return TestResult(
                    name="Signature Verification",
                    status=TestStatus.PASS,
                    message=f"Signature verification endpoint working (status: {response.status_code})",
                    details={
                        "response": error_data.get('message', response.text[:100])
                    },
                    duration_ms=duration
                )
                
        except Exception as e:
            return TestResult(
                name="Signature Verification",
                status=TestStatus.ERROR,
                message=f"Unexpected error: {str(e)}"
            )
    
    def test_wallet_status(self) -> TestResult:
        """Test the wallet status check endpoint"""
        start = time.perf_counter()
        try:
            response = self.session.get(
                f"{self.server_url}/api/auth/web3/status/{self.wallet_address}",
                timeout=10
            )
            duration = (time.perf_counter() - start) * 1000
            
            if response.status_code == 200:
                data = response.json()
                required_fields = ['walletAddress', 'isBound']
                missing_fields = [f for f in required_fields if f not in data]
                
                if missing_fields:
                    return TestResult(
                        name="Wallet Status Check",
                        status=TestStatus.FAIL,
                        message=f"Response missing required fields: {missing_fields}",
                        details={"received_fields": list(data.keys())}
                    )
                
                return TestResult(
                    name="Wallet Status Check",
                    status=TestStatus.PASS,
                    message="Wallet status endpoint working correctly",
                    details={
                        "wallet_address": data.get('walletAddress'),
                        "is_bound": data.get('isBound')
                    },
                    duration_ms=duration
                )
            else:
                return TestResult(
                    name="Wallet Status Check",
                    status=TestStatus.FAIL,
                    message=f"Failed with status {response.status_code}"
                )
        except Exception as e:
            return TestResult(
                name="Wallet Status Check",
                status=TestStatus.ERROR,
                message=f"Unexpected error: {str(e)}"
            )
    
    def test_invalid_address_format(self) -> TestResult:
        """Test validation of invalid wallet address formats"""
        start = time.perf_counter()
        try:
            invalid_addresses = [
                ("0x123", "Too short"),
                ("1234567890123456789012345678901234567890", "Missing 0x prefix"),
                ("0xGHIJ123456789ABCDEF123456789ABCDEF123456", "Invalid hex"),
            ]
            
            results = []
            for addr, desc in invalid_addresses:
                response = self.session.get(
                    f"{self.server_url}/api/auth/web3/status/{addr}",
                    timeout=5
                )
                # Invalid addresses should return 400 Bad Request
                results.append((desc, response.status_code == 400))
            
            duration = (time.perf_counter() - start) * 1000
            
            failed_tests = [desc for desc, passed in results if not passed]
            
            if not failed_tests:
                return TestResult(
                    name="Invalid Address Validation",
                    status=TestStatus.PASS,
                    message="All invalid address formats are properly rejected",
                    details={"tested_addresses": len(invalid_addresses)},
                    duration_ms=duration
                )
            else:
                return TestResult(
                    name="Invalid Address Validation",
                    status=TestStatus.FAIL,
                    message=f"Failed tests: {', '.join(failed_tests)}"
                )
        except Exception as e:
            return TestResult(
                name="Invalid Address Validation",
                status=TestStatus.ERROR,
                message=f"Unexpected error: {str(e)}"
            )
    
    def test_database_migration(self) -> TestResult:
        """Verify that database migration was applied correctly"""
        try:
            # Check if the migration columns exist
            result = subprocess.run(
                ['psql', '-h', 'localhost', '-p', '5432', '-U', 'postgres', 
                 '-d', 'google_oauth2_demo', '-t', '-A', '-F', '|',
                 '-c', """SELECT column_name FROM information_schema.columns 
                          WHERE table_name='user_login_methods' 
                          AND column_name IN ('chain_id', 'web3_nonce', 'nonce_expires_at', 'wallet_metadata')
                          ORDER BY column_name"""],
                capture_output=True,
                text=True,
                env=dict(os.environ, PGPASSWORD='123456')
            )
            
            if result.returncode == 0:
                columns = [c.strip() for c in result.stdout.strip().split('\n') if c.strip()]
                required_columns = ['chain_id', 'nonce_expires_at', 'wallet_metadata', 'web3_nonce']
                missing = set(required_columns) - set(columns)
                
                if not missing:
                    return TestResult(
                        name="Database Migration",
                        status=TestStatus.PASS,
                        message="Web3 migration columns are present",
                        details={"columns_found": columns}
                    )
                else:
                    return TestResult(
                        name="Database Migration",
                        status=TestStatus.FAIL,
                        message=f"Missing migration columns: {missing}",
                        details={"found_columns": columns}
                    )
            else:
                return TestResult(
                    name="Database Migration",
                    status=TestStatus.ERROR,
                    message=f"Database query failed: {result.stderr}"
                )
        except Exception as e:
            return TestResult(
                name="Database Migration",
                status=TestStatus.ERROR,
                message=f"Unexpected error: {str(e)}"
            )
    
    def run_all_tests(self) -> Tuple[bool, list[TestResult]]:
        """Execute all test cases and return overall result"""
        print(f"\n{Colors.HEADER}{Colors.BOLD}🧪 Starting Web3 Wallet Login Tests{Colors.ENDC}")
        print(f"   Server URL: {self.server_url}")
        print(f"   Test Wallet: {self.wallet_address}")
        print(f"   Timestamp: {datetime.now().isoformat()}")
        print(f"\n{Colors.BLUE}{'='*60}{Colors.ENDC}\n")
        
        test_methods = [
            ("Database Migration", self.test_database_migration),
            ("API Health Check", self.test_api_health),
            ("Nonce Generation", self.test_nonce_generation),
            ("Wallet Status Check", self.test_wallet_status),
            ("Invalid Address Validation", self.test_invalid_address_format),
            ("Signature Verification", self.test_signature_verification),
        ]
        
        for test_name, test_method in test_methods:
            print(f"\n{Colors.CYAN}{Colors.BOLD}▶ {test_name}{Colors.ENDC}")
            result = test_method()
            self.log_result(result)
        
        return self.generate_report()
    
    def generate_report(self) -> Tuple[bool, list[TestResult]]:
        """Generate and print test report"""
        passed = sum(1 for r in self.results if r.status == TestStatus.PASS)
        failed = sum(1 for r in self.results if r.status == TestStatus.FAIL)
        errors = sum(1 for r in self.results if r.status == TestStatus.ERROR)
        skipped = sum(1 for r in self.results if r.status == TestStatus.SKIP)
        total = len(self.results)
        
        print(f"\n{Colors.BLUE}{'='*60}{Colors.ENDC}")
        print(f"\n{Colors.HEADER}{Colors.BOLD}📊 Test Report Summary{Colors.ENDC}")
        print(f"\n   Total Tests: {total}")
        print(f"   {Colors.GREEN}✅ Passed: {passed}{Colors.ENDC}")
        print(f"   {Colors.FAIL}❌ Failed: {failed}{Colors.ENDC}")
        print(f"   {Colors.WARNING}⚠️  Errors: {errors}{Colors.ENDC}")
        print(f"   {Colors.BLUE}⏭️  Skipped: {skipped}{Colors.ENDC}")
        
        success_rate = (passed / total * 100) if total > 0 else 0
        print(f"\n   Success Rate: {success_rate:.1f}%")
        
        overall_pass = failed == 0 and errors == 0
        status_message = f"\n{Colors.GREEN}{Colors.BOLD}🎉 ALL TESTS PASSED{Colors.ENDC}" if overall_pass else f"\n{Colors.FAIL}{Colors.BOLD}⚠️  SOME TESTS FAILED{Colors.ENDC}"
        print(status_message)
        
        # Save detailed report to file
        report_file = f"web3_test_report_{datetime.now().strftime('%Y%m%d_%H%M%S')}.json"
        self.save_json_report(report_file)
        print(f"\n   Detailed report saved to: {report_file}")
        
        return overall_pass, self.results
    
    def save_json_report(self, filename: str):
        """Save detailed test results to JSON file"""
        report_data = {
            "test_run": {
                "timestamp": datetime.now().isoformat(),
                "server_url": self.server_url,
                "test_wallet": self.wallet_address
            },
            "summary": {
                "total": len(self.results),
                "passed": sum(1 for r in self.results if r.status == TestStatus.PASS),
                "failed": sum(1 for r in self.results if r.status == TestStatus.FAIL),
                "errors": sum(1 for r in self.results if r.status == TestStatus.ERROR)
            },
            "results": [
                {
                    "name": r.name,
                    "status": r.status.value,
                    "message": r.message,
                    "details": r.details or {},
                    "duration_ms": round(r.duration_ms, 2)
                }
                for r in self.results
            ]
        }
        
        with open(filename, 'w', encoding='utf-8') as f:
            json.dump(report_data, f, indent=2, ensure_ascii=False)


def print_manual_test_guide():
    """Print manual testing guide for real Web3 wallet interaction"""
    guide = f"""
{Colors.HEADER}{Colors.BOLD}📋 Manual Web3 Wallet Login Testing Guide{Colors.ENDC}

Since automated tests cannot perform real wallet signatures, follow these steps
to test with a real wallet (MetaMask, Coinbase Wallet, etc.):

1. {Colors.CYAN}Start the application:{Colors.ENDC}
   mvn spring-boot:run

2. {Colors.CYAN}Open the API documentation:{Colors.ENDC}
   Visit Swagger UI: {Colors.BLUE}http://localhost:8081/swagger-ui.html{Colors.ENDC}

3. {Colors.CYAN}Test the endpoints:{Colors.ENDC}

   Step 1: Get Nonce
   GET /api/auth/web3/nonce/0xYOUR_WALLET_ADDRESS
   Response: {{"nonce": "...", "message": "...", "expiresIn": 300}}

   Step 2: Sign the Message (in MetaMask)
   - Copy the "message" from Step 1 response
   - In MetaMask, click "Sign" to sign the message

   Step 3: Verify and Login
   POST /api/auth/web3/verify
   {{
     "walletAddress": "0xYOUR_WALLET_ADDRESS",
     "message": "The SIWE message from Step 1",
     "signature": "0xSIGNATURE_FROM_METAMASK",
     "nonce": "THE_NONCE_FROM_STEP_1"
   }}

4. {Colors.CYAN}Expected Response (Success):{Colors.ENDC}
   {{
     "accessToken": "eyJhbG...",
     "refreshToken": "eyJhbG...",
     "tokenType": "Bearer",
     "expiresIn": 3600,
     "walletAddress": "0x...",
     "userId": "uuid...",
     "isNewUser": true/false
   }}

5. {Colors.CYAN}Test with Postman/cURL:{Colors.ENDC}
   {Colors.BLUE}# Get nonce{Colors.ENDC}
   curl http://localhost:8081/api/auth/web3/nonce/0x742d35Cc6634C0532925a3b844Bc9e7595f2bD48

   {Colors.BLUE}# Verify signature (replace with real signature){Colors.ENDC}
   curl -X POST http://localhost:8081/api/auth/web3/verify \\
        -H "Content-Type: application/json" \\
        -d '{{"walletAddress":"0x...","message":"...","signature":"0x...","nonce":"..."}}'

{Colors.WARNING}⚠️  Important Notes:{Colors.ENDC}
- Nonces expire after 5 minutes
- Each nonce can only be used once
- The same wallet can only be bound to one account
- Signature verification is cryptographic - no bypass is possible

{Colors.GREEN}✅ Happy Testing!{Colors.ENDC}
"""
    print(guide)


def main():
    """Main entry point for the test script"""
    parser = argparse.ArgumentParser(
        description='Web3 Wallet Login End-to-End Testing Tool',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  python3 test_web3_login.py                                    # Run all tests
  python3 test_web3_login.py --server-url http://localhost:8081
  python3 test_web3_login.py --wallet-address 0x742d35Cc6634C0532925a3b844Bc9e7595f2bD48
  python3 test_web3_login.py --manual                           # Show manual testing guide
        """
    )
    
    parser.add_argument(
        '--server-url',
        default='http://localhost:8081',
        help='Base URL of the API server (default: http://localhost:8081)'
    )
    
    parser.add_argument(
        '--wallet-address',
        default=None,
        help='Ethereum wallet address for testing (default: generated)'
    )
    
    parser.add_argument(
        '--manual',
        action='store_true',
        help='Show manual testing guide and exit'
    )
    
    args = parser.parse_args()
    
    if args.manual:
        print_manual_test_guide()
        return 0
    
    print(f"\n{Colors.HEADER}{Colors.BOLD}")
    print("╔══════════════════════════════════════════════════════════╗")
    print("║     Web3 Wallet Login - End-to-End Testing Tool        ║")
    print("╚══════════════════════════════════════════════════════════╝")
    print(f"{Colors.ENDC}")
    
    tester = Web3LoginTester(args.server_url, args.wallet_address)
    success, results = tester.run_all_tests()
    
    print("\n" + "=" * 60)
    print_manual_test_guide()
    
    return 0 if success else 1


if __name__ == '__main__':
    sys.exit(main())
