# MIFARE Hotel Key Encoder - Android App

A comprehensive Android application for programming MIFARE Classic cards using NFC-enabled Android devices, specifically designed for hotel key encoding with Salto access control systems.

## Features

- **NFC Card Reading**: Scan and analyze existing MIFARE Classic cards
- **Salto Protocol Analysis**: Decode Salto-specific data structures and formats
- **Custom Card Programming**: Create and write custom access configurations
- **Remote Integration**: Connect to backend systems for configuration management
- **Security Focused**: Educational tool with security warnings and best practices

## Requirements

### Hardware
- Android device with NFC capability (API 24+)
- MIFARE Classic 1K or 4K cards
- NFC-enabled smartphone or tablet

### Software
- Android 7.0 (API level 24) or higher
- NFC enabled on the device
- Internet connection for remote features

## Installation

### From Source (Replit)
1. Clone or fork this Replit project
2. Build the APK using the Android build system
3. Install on your NFC-enabled Android device
4. Grant NFC permissions when prompted

### Setup
1. Enable NFC on your Android device
2. Launch the app and review security warnings
3. Configure backend URL if using remote features
4. Begin scanning existing cards or creating new configurations

## Usage

### Scanning Cards
1. Open the app and select "Scan Card"
2. Hold a MIFARE Classic card near your device
3. Review the decoded data and Salto protocol analysis
4. Save or export results as needed

### Writing Cards
1. Select "Write Card" from the main menu
2. Enter custom configuration:
   - User ID (numeric)
   - Access doors (comma-separated room numbers)
   - Start date (ISO format: YYYY-MM-DDTHH:MM:SSZ)
   - End date (ISO format: YYYY-MM-DDTHH:MM:SSZ)
   - Notes (optional)
3. Tap "Encode Card" and validate configuration
4. Hold a blank MIFARE Classic card near the device
5. Wait for write confirmation

### Remote Configuration
1. Select "Remote Config" from the main menu
2. Enter backend URL (e.g., http://your-server:5000)
3. Enter API key if required
4. Test connection
5. Fetch pre-configured card data from the backend

## Security Considerations

⚠️ **IMPORTANT SECURITY NOTICE** ⚠️

This application is designed for **EDUCATIONAL PURPOSES ONLY**. 

- Modifying access control systems may violate laws and organizational policies
- Always obtain proper authorization before analyzing or programming access cards
- Do not use on production systems without explicit permission
- Respect privacy and security of existing access control installations
- Keep the app updated with latest security patches

## Backend Integration

The app integrates with the MIFARE Web App backend through RESTful APIs:

### Endpoints
- `GET /api/test` - Test connectivity
- `POST /api/android/generate-config` - Generate card configurations
- `POST /api/android/programming-result` - Submit programming results
- `GET /api/android/programs` - List available programs

### Authentication
Use Bearer tokens for API authentication:
```
Authorization: Bearer your-api-key-here
```

## Development

### Project Structure
```
app/
├── src/main/java/com/mifare/encoder/
│   ├── MainActivity.kt                 # Main entry point
│   ├── CardScanActivity.kt            # NFC scanning functionality
│   ├── CardWriteActivity.kt           # Card programming
│   ├── RemoteConfigActivity.kt        # Backend integration
│   ├── models/                        # Data models
│   │   ├── CardData.kt
│   │   └── SaltoData.kt
│   ├── utils/                         # Utility classes
│   │   ├── MifareUtils.kt            # MIFARE operations
│   │   ├── SaltoProtocol.kt          # Protocol analysis
│   │   └── ApiClient.kt              # Network operations
│   └── adapters/                      # UI adapters
│       └── CardDataAdapter.kt
├── src/main/res/                      # Resources
│   ├── layout/                        # UI layouts
│   ├── values/                        # Strings, colors, themes
│   └── xml/                          # NFC tech filters
└── AndroidManifest.xml               # App configuration
```

### Key Dependencies
- **NFC**: Android NFC API for MIFARE Classic operations
- **Networking**: OkHttp for HTTP client functionality
- **JSON**: Gson for JSON parsing and serialization
- **Cryptography**: BouncyCastle for crypto operations
- **UI**: Material Design components

### Building
```bash
./gradlew assembleDebug     # Debug build
./gradlew assembleRelease   # Release build
```

## Troubleshooting

### Common Issues

**NFC Not Working**
- Ensure NFC is enabled in device settings
- Check app has NFC permissions
- Try restarting the app and/or device

**Card Authentication Failed**
- Default MIFARE keys may not work on programmed cards
- Contact card issuer for proper authentication keys
- Some cards may have custom key structures

**Backend Connection Issues**
- Verify backend URL is correct and accessible
- Check API key if authentication is required
- Ensure device has internet connectivity
- Verify backend server is running

**Write Failures**
- Use blank or properly formatted cards
- Ensure card is held steady during write operation
- Check that card supports write operations
- Verify sufficient data space on card

## Legal Compliance

- **Educational Use**: This tool is intended for learning about RFID/NFC technology
- **Authorization Required**: Only use on systems you own or have explicit permission to test
- **No Warranty**: Software provided as-is without warranties
- **Responsible Disclosure**: Report security issues responsibly to system administrators

## Contributing

This is an educational project. Contributions should focus on:
- Improving code security and best practices
- Enhancing educational value
- Adding better error handling and user guidance
- Improving documentation and examples

## License

Educational use only. Not for commercial deployment without proper security review.

## Disclaimer

The developers are not responsible for misuse of this tool. Users must comply with all applicable laws and regulations regarding access control systems and RFID technology.