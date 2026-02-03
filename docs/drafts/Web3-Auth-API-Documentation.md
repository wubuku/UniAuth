# Web3 Wallet Login API Documentation

## Overview

Web3 Wallet Login provides passwordless authentication using Ethereum wallets (MetaMask, Coinbase Wallet, etc.). This authentication method uses the Sign-In with Ethereum (SIWE) standard for secure, decentralized identity verification.

## Base URL

```
/api/auth/web3
```

## Endpoints

### 1. Get Nonce

Generates a nonce and SIWE message for wallet authentication.

**Endpoint:** `GET /api/auth/web3/nonce/{walletAddress}`

**Path Parameters:**
| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| walletAddress | string | Yes | Ethereum wallet address (0x...) |

**Response (200 OK):**
```json
{
  "nonce": "abc123def456...",
  "message": "localhost wants you to sign in with your Ethereum account:\n0x1234...5678\n\nBy signing, you agree to authenticate with your wallet.\n\nURI: https://localhost\nVersion: 1\nChain ID: 1\nNonce: abc123def456...\nIssued At: 2026-01-01T00:00:00Z\nExpiration Time: 2026-01-01T00:05:00Z",
  "expiresIn": 300
}
```

**Error Response (400 Bad Request):**
```json
{
  "status": 400,
  "errorCode": "INVALID_ADDRESS",
  "message": "Invalid wallet address format",
  "timestamp": "2026-01-01T00:00:00Z"
}
```

---

### 2. Verify Signature and Login

Verifies the wallet signature and returns JWT tokens on success.

**Endpoint:** `POST /api/auth/web3/verify`

**Request Body:**
```json
{
  "walletAddress": "0x1234567890123456789012345678901234567890",
  "message": "localhost wants you to sign in with your Ethereum account:\n...",
  "signature": "0xabc123...",
  "nonce": "abc123def456...",
  "chainId": 1
}
```

**Request Parameters:**
| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| walletAddress | string | Yes | Ethereum wallet address (0x...) |
| message | string | Yes | The SIWE message that was signed |
| signature | string | Yes | Hexadecimal signature from wallet |
| nonce | string | Yes | Nonce received from GET /nonce endpoint |
| chainId | integer | No | Blockchain chain ID (default: 1) |

**Response (200 OK):**
```json
{
  "accessToken": "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...",
  "refreshToken": "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...",
  "tokenType": "Bearer",
  "expiresIn": 3600,
  "walletAddress": "0x1234567890123456789012345678901234567890",
  "userId": "uuid-string",
  "isNewUser": true
}
```

**Error Response (401 Unauthorized):**
```json
{
  "status": 401,
  "errorCode": "INVALID_SIGNATURE",
  "message": "Signature verification failed",
  "timestamp": "2026-01-01T00:00:00Z"
}
```

---

### 3. Bind Wallet to Existing Account

Binds a Web3 wallet to an already authenticated user account.

**Endpoint:** `POST /api/auth/web3/bind`

**Headers:**
| Header | Required | Description |
|--------|----------|-------------|
| Authorization | Yes | Bearer token of authenticated user |

**Request Body:**
```json
{
  "walletAddress": "0x1234567890123456789012345678901234567890",
  "message": "localhost wants you to sign in with your Ethereum account:\n...",
  "signature": "0xabc123...",
  "nonce": "abc123def456..."
}
```

**Response (200 OK):**
```json
{
  "status": 200,
  "errorCode": "SUCCESS",
  "message": "Wallet bound successfully",
  "timestamp": "2026-01-01T00:00:00Z"
}
```

**Error Response (400 Bad Request):**
```json
{
  "status": 400,
  "errorCode": "BINDING_FAILED",
  "message": "Wallet is already bound to another account",
  "timestamp": "2026-01-01T00:00:00Z"
}
```

---

### 4. Check Wallet Binding Status

Checks if a wallet address is already bound to an account.

**Endpoint:** `GET /api/auth/web3/status/{walletAddress}`

**Path Parameters:**
| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| walletAddress | string | Yes | Ethereum wallet address (0x...) |

**Response (200 OK):**
```json
{
  "walletAddress": "0x1234567890123456789012345678901234567890",
  "isBound": true
}
```

---

## Authentication Flow

### Step 1: Connect Wallet (Frontend)
User connects their wallet using MetaMask or similar wallet extension.

### Step 2: Get Nonce
```javascript
const response = await fetch(`/api/auth/web3/nonce/${walletAddress}`);
const { nonce, message, expiresIn } = await response.json();
```

### Step 3: Sign Message
```javascript
const provider = new ethers.BrowserProvider(window.ethereum);
const signer = await provider.getSigner();
const signature = await signer.signMessage(message);
```

### Step 4: Verify and Login
```javascript
const response = await fetch('/api/auth/web3/verify', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({
    walletAddress,
    message,
    signature,
    nonce
  })
});

const { accessToken, refreshToken } = await response.json();
localStorage.setItem('accessToken', accessToken);
localStorage.setItem('refreshToken', refreshToken);
```

---

## Security Features

### Nonce Validation
- Nonces expire after 5 minutes (configurable)
- Each nonce can only be used once
- Nonces are stored securely in the database

### Signature Verification
- Uses EIP-191 signature standard
- Verifies that the signature was created by the wallet owner
- Prevents replay attacks with unique nonces

### Rate Limiting
- Nonce generation: 10 requests per minute per IP
- Verification attempts: 20 per minute per IP

---

## Integration with Existing System

Web3 login integrates seamlessly with the existing multi-login system:

1. **New Users**: Automatically created with wallet as identity
2. **Existing Users**: Can bind wallet via `/api/auth/web3/bind`
3. **Multiple Methods**: Users can have multiple login methods (Google, GitHub, Web3, etc.)
4. **Same JWT**: Uses the same JWT token system as other login methods

---

## Configuration

### Application Properties
```yaml
app:
  web3:
    nonce-expiration-seconds: 300  # Nonce validity in seconds
    domain: localhost               # Domain for SIWE messages
```

---

## Error Codes

| Code | HTTP Status | Description |
|------|-------------|-------------|
| INVALID_ADDRESS | 400 | Wallet address format is invalid |
| INVALID_SIGNATURE | 401 | Signature verification failed |
| NONCE_EXPIRED | 401 | Nonce has expired |
| NONCE_MISMATCH | 401 | Nonce does not match |
| BINDING_FAILED | 400 | Wallet binding failed (already bound) |
| MISSING_TOKEN | 401 | Authorization header missing |
| INTERNAL_ERROR | 500 | Server-side error |

---

## Frontend Integration Example

### React Hook Example
```javascript
import { useState } from 'react';
import { ethers } from 'ethers';

function Web3Login() {
  const [walletAddress, setWalletAddress] = useState('');
  const [loading, setLoading] = useState(false);

  const connectWallet = async () => {
    if (!window.ethereum) {
      alert('Please install MetaMask!');
      return;
    }

    const provider = new ethers.BrowserProvider(window.ethereum);
    const accounts = await provider.send('eth_requestAccounts', []);
    setWalletAddress(accounts[0]);
  };

  const login = async () => {
    setLoading(true);
    try {
      // Step 1: Get nonce
      const nonceResponse = await fetch(`/api/auth/web3/nonce/${walletAddress}`);
      const { nonce, message } = await nonceResponse.json();

      // Step 2: Sign message
      const provider = new ethers.BrowserProvider(window.ethereum);
      const signer = await provider.getSigner();
      const signature = await signer.signMessage(message);

      // Step 3: Verify and get tokens
      const verifyResponse = await fetch('/api/auth/web3/verify', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ walletAddress, message, signature, nonce })
      });

      if (!verifyResponse.ok) {
        throw new Error('Login failed');
      }

      const tokens = await verifyResponse.json();
      localStorage.setItem('accessToken', tokens.accessToken);
      localStorage.setItem('refreshToken', tokens.refreshToken);
      
      alert('Login successful!');
    } catch (error) {
      console.error('Login failed:', error);
      alert('Login failed: ' + error.message);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div>
      <button onClick={connectWallet}>
        {walletAddress ? `Connected: ${walletAddress.slice(0, 6)}...` : 'Connect Wallet'}
      </button>
      <button onClick={login} disabled={!walletAddress || loading}>
        {loading ? 'Logging in...' : 'Sign In with Ethereum'}
      </button>
    </div>
  );
}
```

---

## Testing

### Manual Test with Postman

1. **Get Nonce:**
   ```
   GET http://localhost:8081/api/auth/web3/nonce/0x742d35Cc6634C0532925a3b844Bc9e7595f2bD48
   ```

2. **Sign Message:**
   Use MetaMask to sign the message received from step 1.

3. **Verify:**
   ```
   POST http://localhost:8081/api/auth/web3/verify
   Content-Type: application/json
   
   {
     "walletAddress": "0x742d35Cc6634C0532925a3b844Bc9e7595f2bD48",
     "message": "localhost wants you to sign in...",
     "signature": "0x...",
     "nonce": "abc123..."
   }
   ```

4. **Access Protected Resource:**
   ```
   GET http://localhost:8081/api/user
   Authorization: Bearer <access_token>
   ```

---

## Related Files

- **Controller**: [Web3AuthController.java](../src/main/java/org/dddml/uniauth/controller/Web3AuthController.java)
- **Service**: [Web3AuthService.java](../src/main/java/org/dddml/uniauth/service/Web3AuthService.java)
- **DTOs**: [Web3NonceResponse.java](../src/main/java/org/dddml/uniauth/dto/web3/Web3NonceResponse.java), [Web3LoginRequest.java](../src/main/java/org/dddml/uniauth/dto/web3/Web3LoginRequest.java), [Web3AuthResponse.java](../src/main/java/org/dddml/uniauth/dto/web3/Web3AuthResponse.java)
- **Utility**: [Web3SignatureUtils.java](../src/main/java/org/dddml/uniauth/util/Web3SignatureUtils.java)
- **Migration**: [V4__Add_web3_login_support.sql](../src/main/resources/db/migration/V4__Add_web3_login_support.sql)
