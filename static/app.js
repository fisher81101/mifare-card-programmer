// MIFARE Card Programming System - Client-side JavaScript

// Global variables
let currentCardData = null;
let programmingInProgress = false;

// Initialize application
document.addEventListener('DOMContentLoaded', function() {
    initializeApp();
});

function initializeApp() {
    // Check for NFC support
    if ('NDEFReader' in window) {
        console.log('NFC supported');
        document.body.classList.add('nfc-supported');
    } else {
        console.log('NFC not supported');
        document.body.classList.add('nfc-not-supported');
    }
    
    // Fix form detection with better timing and selectors
    setTimeout(function() {
        const forms = document.querySelectorAll('form');
        console.log(`🔧 Found ${forms.length} form(s) on page`);
        
        forms.forEach(function(form, index) {
            console.log(`🔧 Form ${index}: method="${form.method}", action="${form.action}"`);
            
            if (form.method.toLowerCase() === 'post') {
                console.log('🔧 Adding submit listener to POST form');
                
                form.addEventListener('submit', function(e) {
                    console.log('🔧 Form submission intercepted!');
                    console.log('🔧 Target URL:', form.action || window.location.href);
                    
                    const formData = new FormData(form);
                    console.log('🔧 Form data being submitted:');
                    for (let [key, value] of formData.entries()) {
                        console.log(`  ${key}: ${key.includes('password') ? '***' : value}`);
                    }
                });
            }
        });
    }, 500); // Wait 500ms for all content to load
    
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
            bsAlert.close();
        });
    }, 5000);
}

// Utility functions
function showLoading(element, text = 'Loading...') {
    if (typeof element === 'string') {
        element = document.getElementById(element);
    }
    element.innerHTML = `
        <div class="text-center">
            <div class="spinner-border text-primary" role="status">
                <span class="visually-hidden">Loading...</span>
            </div>
            <p class="mt-2">${text}</p>
        </div>
    `;
}

function hideLoading(element) {
    if (typeof element === 'string') {
        element = document.getElementById(element);
    }
    element.innerHTML = '';
}

function showError(message, container = null) {
    const errorHtml = `
        <div class="alert alert-danger alert-dismissible fade show" role="alert">
            <i class="fas fa-exclamation-triangle me-2"></i>
            ${message}
            <button type="button" class="btn-close" data-bs-dismiss="alert"></button>
        </div>
    `;
    
    if (container) {
        container.innerHTML = errorHtml;
    } else {
        // Add to top of main container
        const main = document.querySelector('main.container');
        const tempDiv = document.createElement('div');
        tempDiv.innerHTML = errorHtml;
        main.insertBefore(tempDiv.firstElementChild, main.firstElementChild);
    }
}

function showSuccess(message, container = null) {
    const successHtml = `
        <div class="alert alert-success alert-dismissible fade show" role="alert">
            <i class="fas fa-check-circle me-2"></i>
            ${message}
            <button type="button" class="btn-close" data-bs-dismiss="alert"></button>
        </div>
    `;
    
    if (container) {
        container.innerHTML = successHtml;
    } else {
        // Add to top of main container
        const main = document.querySelector('main.container');
        const tempDiv = document.createElement('div');
        tempDiv.innerHTML = successHtml;
        main.insertBefore(tempDiv.firstElementChild, main.firstElementChild);
    }
}

// Card scanning functions
async function scanForCards() {
    try {
        const response = await fetch('/api/scan_card');
        const data = await response.json();
        
        if (data.success && data.cards.length > 0) {
            return data.cards;
        } else {
            throw new Error('No cards found');
        }
    } catch (error) {
        throw new Error('Failed to scan for cards: ' + error.message);
    }
}

// NFC Programming functions
class NFCProgrammer {
    constructor() {
        this.reader = null;
        this.isScanning = false;
        this.onCardDetected = null;
        this.onError = null;
        this.onProgress = null;
    }
    
    async initialize() {
        if (!('NDEFReader' in window)) {
            throw new Error('NFC not supported on this device');
        }
        
        this.reader = new NDEFReader();
        return true;
    }
    
    async startScanning() {
        if (!this.reader) {
            await this.initialize();
        }
        
        try {
            await this.reader.scan();
            this.isScanning = true;
            
            this.reader.addEventListener('reading', (event) => {
                if (this.onCardDetected) {
                    this.onCardDetected(event);
                }
            });
            
            this.reader.addEventListener('readingerror', (error) => {
                if (this.onError) {
                    this.onError(error);
                }
            });
            
        } catch (error) {
            throw new Error('Failed to start NFC scanning: ' + error.message);
        }
    }
    
    async programCard(cardData, progressCallback) {
        if (!cardData || !cardData.sector_data) {
            throw new Error('Invalid card data');
        }
        
        const sectors = Object.keys(cardData.sector_data);
        const totalSectors = sectors.length;
        
        for (let i = 0; i < totalSectors; i++) {
            const sectorNum = sectors[i];
            const sectorData = cardData.sector_data[sectorNum];
            
            // Simulate programming delay
            await new Promise(resolve => setTimeout(resolve, 200));
            
            if (progressCallback) {
                const progress = ((i + 1) / totalSectors) * 100;
                progressCallback(progress, `Programming sector ${sectorNum}...`);
            }
        }
        
        return true;
    }
    
    stopScanning() {
        this.isScanning = false;
        // Note: NDEFReader doesn't have a stop method, scanning stops automatically
    }
}

// Hex validation and formatting
function validateHexString(hex, expectedLength = null) {
    const cleanHex = hex.replace(/[^0-9A-Fa-f]/g, '');
    
    if (expectedLength && cleanHex.length !== expectedLength) {
        return false;
    }
    
    return /^[0-9A-Fa-f]*$/.test(cleanHex);
}

function formatHexString(hex, groupSize = 2, separator = ' ') {
    const cleanHex = hex.replace(/[^0-9A-Fa-f]/g, '').toUpperCase();
    const groups = [];
    
    for (let i = 0; i < cleanHex.length; i += groupSize) {
        groups.push(cleanHex.substr(i, groupSize));
    }
    
    return groups.join(separator);
}

function padHexString(hex, length) {
    const cleanHex = hex.replace(/[^0-9A-Fa-f]/g, '').toUpperCase();
    return cleanHex.padEnd(length, '0');
}

// Sector data validation
function validateSectorData(sectorData) {
    const errors = [];
    
    if (!sectorData || typeof sectorData !== 'object') {
        errors.push('Invalid sector data format');
        return errors;
    }
    
    for (const [sectorNum, sector] of Object.entries(sectorData)) {
        if (!sector.blocks || !Array.isArray(sector.blocks)) {
            errors.push(`Sector ${sectorNum}: Missing or invalid blocks array`);
            continue;
        }
        
        if (sector.blocks.length !== 4) {
            errors.push(`Sector ${sectorNum}: Must have exactly 4 blocks`);
        }
        
        for (let i = 0; i < sector.blocks.length; i++) {
            const block = sector.blocks[i];
            if (!validateHexString(block, 32)) {
                errors.push(`Sector ${sectorNum}, Block ${i}: Invalid hex data (must be 32 hex characters)`);
            }
        }
        
        if (sector.keys) {
            if (sector.keys.keyA && !validateHexString(sector.keys.keyA, 12)) {
                errors.push(`Sector ${sectorNum}: Invalid Key A (must be 12 hex characters)`);
            }
            if (sector.keys.keyB && !validateHexString(sector.keys.keyB, 12)) {
                errors.push(`Sector ${sectorNum}: Invalid Key B (must be 12 hex characters)`);
            }
        }
    }
    
    return errors;
}

// Export functions for global access
window.MifareApp = {
    scanForCards,
    NFCProgrammer,
    validateHexString,
    formatHexString,
    padHexString,
    validateSectorData,
    showLoading,
    hideLoading,
    showError,
    showSuccess
};
