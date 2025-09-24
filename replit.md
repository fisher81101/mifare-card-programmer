# Overview

This is a comprehensive MIFARE Card Programming System consisting of:
1. **Flask Web Application**: Web-based platform for creating, managing, and distributing MIFARE Classic card programming data
2. **Dual-Mode Android Application**: Production-ready Android app with separated user and admin interfaces for NFC-enabled MIFARE card programming

The system enables administrators to create card programs using a visual sector editor and securely distribute programming capabilities. The Android application provides both a simplified user interface for automatic card programming and a password-protected admin interface with full functionality for educational and hotel key card encoding scenarios.

## Project Status: COMPLETED ✅ + PRODUCTION READY 🚀
- ✅ **NATIVE ANDROID APK**: 11MB production-ready application with complete NFC MIFARE programming
- ✅ **FULL FUNCTIONALITY**: Scan, write, analyze, and manage MIFARE Classic 1K/4K cards
- ✅ **UI COMPLETE**: Working RecyclerView displaying scanned card details with sector data
- ✅ **COMPILATION SUCCESS**: All Kotlin errors resolved, clean build process achieved
- ✅ **SDK PERSISTENCE**: Automated Android SDK setup with workspace storage solution
- ✅ Flask web application with complete MIFARE card management
- ✅ Dual-mode Android application with enterprise-grade security
- ✅ Production-ready security implementation with encrypted storage
- ✅ Complete NFC integration for MIFARE Classic 1K/4K cards
- ✅ Authentication system with admin/user separation
- ✅ Automatic backend integration for seamless operation
- 🚀 **COMPLETE APK AUTOMATION**: GitHub Actions + Automatic Download System
- 🔒 **ENTERPRISE SECURITY**: Webhook signature verification, concurrency protection
- ⚡ **ZERO-TOUCH DEPLOYMENT**: Code push → APK build → Auto-download → Ready for users

# User Preferences

Preferred communication style: Simple, everyday language.

# Android Application Admin Access

## Security Implementation
- **Admin Password**: Default "admin123" requires immediate change on first run to minimum 8 characters
- **Access Method**: 7 consecutive clicks on app logo within 500ms intervals
- **Authentication**: Password-protected with session timeout (30 minutes)
- **Data Security**: All sensitive data encrypted with AES256 (EncryptedSharedPreferences)
- **Network Security**: HTTPS-only for production, cleartext allowed only for local development

## Admin Interface Features
1. **Scan Tab**: Read and analyze existing MIFARE cards with detailed hex dump display
2. **Write Tab**: Program cards with custom data and sector/block configuration
3. **Config Tab**: Backend integration settings, user mode configuration, password management
4. **Logs Tab**: Comprehensive admin activity logging with timestamps

## User Mode Features
- **Automatic Operation**: Simplified interface with backend-driven card programming
- **Hidden Admin Access**: 7-click gesture on logo to reveal admin password prompt
- **Secure Configuration**: All settings managed through encrypted admin interface
- **Error Handling**: User-friendly error messages with admin logging

## Deployment Notes
- **Production Ready**: Complete security hardening with encrypted storage
- **Educational Use**: Designed for controlled environments with proper disclaimers
- **Network Configuration**: Supports both local development and HTTPS production deployment

# System Architecture

## Frontend Architecture
- **Framework**: Bootstrap 5 with custom CSS for responsive web interface
- **JavaScript**: Vanilla JavaScript with NFC Web API integration for client-side card operations
- **Templates**: Jinja2 templating engine with Flask for server-side rendering
- **Mobile-First Design**: Responsive interface optimized for Android devices with NFC capability

## Backend Architecture
- **Web Framework**: Flask with SQLAlchemy ORM for database operations
- **Authentication**: Flask-Login with session-based user management and role-based access control
- **Security**: One-time access tokens with 24-hour expiry, JWT for secure link generation
- **Data Processing**: JSON-based sector data storage with validation and formatting utilities

## Database Design
- **User Management**: Users table with admin/regular user roles and authentication
- **Program Storage**: CardProgram table storing MIFARE sector data in JSON format
- **Distribution Tracking**: ProgramDistribution table managing secure links and usage tracking
- **Database Support**: SQLite for development, PostgreSQL for production with automatic migration

## MIFARE Card Operations
- **Card Types**: Support for MIFARE Classic 1K/4K with configurable sector layouts
- **Data Structure**: JSON-based sector/block data storage with key management
- **Programming Interface**: Web NFC API for browser-based programming with hardware fallbacks
- **Security Keys**: Configurable authentication keys for sector access control

## NFC Implementation Strategy
- **Primary Method**: Web NFC API through Android Chrome browser
- **Limitation Handling**: NDEF-based approach due to Web NFC API restrictions on raw MIFARE programming
- **Companion App**: Android APK for native NFC access when web limitations prevent proper programming
- **Mobile Redirect**: Intelligent device detection with app deep-linking and APK distribution

## Security Architecture
- **Token-Based Access**: Cryptographically secure one-time tokens for program distribution
- **Role-Based Permissions**: Admin/user separation with protected administrative functions
- **Session Management**: Flask-Login with secure session handling and automatic expiry
- **Data Validation**: Input sanitization and JSON schema validation for sector data

## Cloud Deployment Strategy
- **Platform Agnostic**: Configured for Railway, Render, Netlify, and Vercel deployment
- **Environment Configuration**: Environment variable-based configuration for production settings
- **Dependency Management**: Hardware-agnostic dependencies removing smartcard libraries for cloud compatibility
- **Database Persistence**: PostgreSQL for production with automatic table creation and admin user setup

# External Dependencies

## Core Web Framework
- **Flask**: Main web framework with SQLAlchemy, Login, WTF, CORS, and Mail extensions
- **Database**: PostgreSQL (production) with psycopg2-binary driver, SQLite3 (development)
- **Security**: Werkzeug for password hashing, PyJWT for token generation, bcrypt for additional security

## Frontend Libraries
- **Bootstrap 5**: UI framework via CDN for responsive design and components
- **Font Awesome 6**: Icon library via CDN for consistent iconography
- **NFC Web API**: Browser-based NFC functionality for Android Chrome

## Cryptography and Utilities
- **PyCryptodome**: MIFARE-specific cryptographic operations and key management
- **QRCode with Pillow**: QR code generation for easy link sharing and mobile access
- **Requests**: HTTP client for external API integration and companion app communication

## Development and Deployment
- **Python-dotenv**: Environment variable management for configuration
- **Click with Colorama**: Command-line interface utilities for administration scripts
- **Email-validator**: Input validation for user registration and management

## External Services Integration
- **GitHub Releases**: APK distribution for companion Android app via direct download links
- **Cloud Platforms**: Railway (recommended), Render, Netlify, Vercel for web application hosting
- **Domain Management**: Custom subdomain support for programmer.513solutions.com deployment