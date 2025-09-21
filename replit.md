# Overview

This is a MIFARE Card Programming System - a comprehensive web-based platform for creating, managing, and distributing MIFARE Classic card programming data. The system enables administrators to create card programs using a visual sector editor and securely distribute one-time programming links to users. Users can then access these links on Android devices to program MIFARE cards via NFC, with fallback options including a companion Android app and web-based NFC interface.

# User Preferences

Preferred communication style: Simple, everyday language.

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