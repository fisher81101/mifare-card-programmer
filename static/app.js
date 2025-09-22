// MIFARE Card Programming System - Client-side JavaScript

// Global variables
let currentCardData = null;
let programmingInProgress = false;

// Initialize application
document.addEventListener('DOMContentLoaded', function() {
    initializeApp();
});

function initializeApp() {
    // Check for NFC support and update status
    const nfcStatus = document.getElementById('nfc-status');
    if (nfcStatus) {
        if ('NDEFReader' in window) {
            nfcStatus.innerHTML = '<i class="fas fa-check-circle text-success me-1"></i>NFC Supported - Ready for card programming';
            document.body.classList.add('nfc-supported');
        } else {
            nfcStatus.innerHTML = '<i class="fas fa-exclamation-triangle text-warning me-1"></i>NFC Not Supported - Use Android Chrome for card programming';
            document.body.classList.add('nfc-not-supported');
        }
    }
    
    // Initialize tooltips
    var tooltipTriggerList = [].slice.call(document.querySelectorAll('[data-bs-toggle="tooltip"]'));
    var tooltipList = tooltipTriggerList.map(function (tooltipTriggerEl) {
        return new bootstrap.Tooltip(tooltipTriggerEl);
    });
    
    // Auto-dismiss alerts after 5 seconds
    setTimeout(function() {
        var alerts = document.querySelectorAll('.alert-dismissible');
        alerts.forEach(function(alert) {
            var bsAlert = new bootstrap.Alert(alert);
            if (bsAlert) {
                bsAlert.close();
            }
        });
    }, 5000);

    // Handle login form submission
    const loginForm = document.getElementById('loginForm');
    if (loginForm) {
        loginForm.addEventListener('submit', function(e) {
            const submitBtn = this.querySelector('button[type="submit"]');
            if (submitBtn) {
                submitBtn.innerHTML = '<i class="fas fa-spinner fa-spin me-2"></i>Signing in...';
                submitBtn.disabled = true;
            }
        });
    }
}

// Utility functions
function showLoading(element, text = 'Loading...') {
    if (typeof element === 'string') {
        element = document.getElementById(element);
    }
    if (element) {
        element.innerHTML = `
            <div class="text-center">
                <div class="spinner-border text-primary" role="status">
                    <span class="visually-hidden">Loading...</span>
                </div>
                <p class="mt-2">${text}</p>
            </div>
        `;
    }
}

function hideLoading(element) {
    if (typeof element === 'string') {
        element = document.getElementById(element);
    }
    if (element) {
        element.innerHTML = '';
    }
}

function showAlert(message, type = 'info') {
    const alertDiv = document.createElement('div');
    alertDiv.className = `alert alert-${type} alert-dismissible fade show`;
    alertDiv.innerHTML = `
        ${message}
        <button type="button" class="btn-close" data-bs-dismiss="alert"></button>
    `;
    
    const container = document.querySelector('.container');
    if (container) {
        container.insertBefore(alertDiv, container.firstChild);
    }
    
    // Auto-dismiss after 5 seconds
    setTimeout(function() {
        const bsAlert = new bootstrap.Alert(alertDiv);
        if (bsAlert) {
            bsAlert.close();
        }
    }, 5000);
}

// NFC Functions
async function startNFCReading() {
    if (!('NDEFReader' in window)) {
        showAlert('NFC is not supported on this device. Please use an Android device with Chrome browser.', 'warning');
        return;
    }

    try {
        const ndef = new NDEFReader();
        await ndef.scan();
        showAlert('NFC scanning started. Hold your device near a MIFARE card.', 'info');

        ndef.addEventListener('reading', ({ message }) => {
            handleNFCReading(message);
        });

        ndef.addEventListener('readingerror', () => {
            showAlert('Error reading NFC card. Please try again.', 'danger');
        });

    } catch (error) {
        console.error('NFC Error:', error);
        showAlert('Failed to start NFC scanning: ' + error.message, 'danger');
    }
}

function handleNFCReading(message) {
    try {
        let cardData = '';
        for (const record of message.records) {
            if (record.recordType === 'text') {
                const textDecoder = new TextDecoder(record.encoding);
                cardData += textDecoder.decode(record.data);
            }
        }
        
        currentCardData = cardData;
        showAlert('Card data read successfully!', 'success');
        
        // Update UI with card data
        const cardDataElement = document.getElementById('cardData');
        if (cardDataElement) {
            cardDataElement.textContent = cardData;
        }
        
    } catch (error) {
        console.error('Card reading error:', error);
        showAlert('Error processing card data: ' + error.message, 'danger');
    }
}

async function writeNFCCard(data) {
    if (!('NDEFReader' in window)) {
        showAlert('NFC is not supported on this device.', 'warning');
        return;
    }

    if (programmingInProgress) {
        showAlert('Programming already in progress. Please wait.', 'warning');
        return;
    }

    try {
        programmingInProgress = true;
        showAlert('Hold your device near the MIFARE card to program it...', 'info');
        
        const ndef = new NDEFReader();
        await ndef.write({
            records: [{
                recordType: 'text',
                data: data
            }]
        });

        showAlert('Card programmed successfully!', 'success');
        
    } catch (error) {
        console.error('NFC write error:', error);
        showAlert('Error programming card: ' + error.message, 'danger');
    } finally {
        programmingInProgress = false;
    }
}

// Program card with current data
function programCard() {
    if (!currentCardData) {
        showAlert('No card data available. Please select a program first.', 'warning');
        return;
    }
    
    writeNFCCard(currentCardData);
}

// Copy data to clipboard
function copyToClipboard(text) {
    if (navigator.clipboard) {
        navigator.clipboard.writeText(text).then(function() {
            showAlert('Copied to clipboard!', 'success');
        }).catch(function(error) {
            console.error('Copy error:', error);
            showAlert('Failed to copy to clipboard', 'danger');
        });
    } else {
        // Fallback for older browsers
        const textArea = document.createElement('textarea');
        textArea.value = text;
        document.body.appendChild(textArea);
        textArea.select();
        try {
            document.execCommand('copy');
            showAlert('Copied to clipboard!', 'success');
        } catch (error) {
            showAlert('Failed to copy to clipboard', 'danger');
        }
        document.body.removeChild(textArea);
    }
}

// Format JSON data
function formatJSON(jsonString) {
    try {
        const parsed = JSON.parse(jsonString);
        return JSON.stringify(parsed, null, 2);
    } catch (error) {
        return jsonString;
    }
}

// Validate sector data
function validateSectorData(data) {
    try {
        const parsed = JSON.parse(data);
        
        // Basic validation - should be an object or array
        if (typeof parsed !== 'object') {
            return { valid: false, error: 'Sector data must be a valid JSON object or array' };
        }
        
        return { valid: true, data: parsed };
    } catch (error) {
        return { valid: false, error: 'Invalid JSON format: ' + error.message };
    }
}

// Mobile app detection and redirection
function detectMobileAndRedirect() {
    const userAgent = navigator.userAgent.toLowerCase();
    const isMobile = /android|iphone|ipad|ipod|blackberry|iemobile|opera mini/.test(userAgent);
    const isAndroid = /android/.test(userAgent);
    
    if (isMobile && !isAndroid) {
        showAlert('For MIFARE card programming, please use an Android device with NFC capability.', 'info');
    }
    
    return { isMobile, isAndroid };
}

// Initialize mobile detection on load
document.addEventListener('DOMContentLoaded', function() {
    detectMobileAndRedirect();
});