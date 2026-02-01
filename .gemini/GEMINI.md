# UniAuth Project Context

## Overview
UniAuth is a Unified Authentication & Authorization system using Spring Boot (Backend) and React (Frontend).
It supports local username/password login and OAuth2 SSO (Google, GitHub, X).
It uses a Heterogeneous Resource Server architecture (Java Auth Server + Python Resource Server example).

## Tech Stack
- **Backend**: Java 17, Spring Boot 3, Maven.
- **Frontend**: React, Vite, TypeScript.
- **Database**: PostgreSQL (Production), SQLite (Dev/Test).
- **Security**: Spring Security 6, OAuth2 Client/Resource Server.

## Key Conventions & Architecture

### Authentication & Tokens
- **JWT Format**:
  - `sub` (Subject): **User ID** (UUID).
  - `username` (Claim): The actual username.
  - **Crucial**: When extracting username, ALWAYS check `username` claim first, fallback to `sub` for backward compatibility.
- **Token Delivery**:
  - **Dual Delivery**: Tokens are sent via **HttpOnly Cookies** (for security) AND **JSON Response Body** (for cross-origin/local usage).
  - **Access Token**: 1 hour expiration.
  - **Refresh Token**: 7 days expiration.

### API Contract
- **Login**: `POST /api/auth/login` -> JSON + Cookies.
- **UserInfo**: `GET /api/user` -> Returns unified user info (provider agnostic).
- **OAuth2**:
  - Starts at `/oauth2/authorization/{provider}`.
  - Handled by `SecurityConfig.oauth2SuccessHandler`.
  - Smart Routing: Can return JSON or Redirect based on `Accept` header or `state` param.

### Frontend-Backend Integration
- **Deployment**: Spring Boot serves the React app static files.
- **Routing**: `SpaController` forwards non-API paths to `index.html` to support client-side routing.
- **Dev**: Frontend runs on port 5173 (Vite), Backend on 8080. Proxy configured in Vite.

## Operational Commands
- **Start Backend**: `mvn spring-boot:run`
- **Start Frontend**: `cd frontend && npm run dev`
- **Build**: `sh build-frontend.sh` (builds frontend to static resources) -> `mvn clean package`.
- **Test**: `mvn test`

## Important File Paths
- **Security Config**: `src/main/java/org/dddml/uniauth/config/SecurityConfig.java`
- **JWT Service**: `src/main/java/org/dddml/uniauth/service/JwtTokenService.java`
- **API Controllers**:
  - `AuthController.java`: Local auth & cookie handling.
  - `ApiAuthController.java`: REST API endpoints (Stateless).
- **Frontend Auth**: `frontend/src/services/authService.ts`

## Recent Changes (Memory)
- **JWT Update (2026-02-02)**: Changed `sub` to store `userId`. Added `username` claim. Updated all extractors to prioritize claim over subject.
- **X (Twitter) Auth**: Configured to use API v2.
