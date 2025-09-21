#!/usr/bin/env python3
"""
GitHub Actions Automation for APK Building and Distribution
Automatically downloads APK artifacts from GitHub Actions builds
"""

import os
import requests
import zipfile
import logging
import json
from datetime import datetime
from pathlib import Path
from typing import Optional, Dict, Any

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)

class GitHubAPKDownloader:
    def __init__(self, owner: str, repo: str, token: Optional[str] = None):
        self.owner = owner
        self.repo = repo
        self.token = token or os.getenv('GITHUB_TOKEN')
        self.base_url = "https://api.github.com"
        self.download_dir = Path("static/downloads")
        
        # Ensure download directory exists
        self.download_dir.mkdir(parents=True, exist_ok=True)
        
        # GitHub API headers
        self.headers = {
            'Accept': 'application/vnd.github+json',
            'X-GitHub-Api-Version': '2022-11-28'
        }
        
        if self.token:
            self.headers['Authorization'] = f'Bearer {self.token}'
        else:
            logger.warning("No GITHUB_TOKEN provided - API calls may fail for private repos or artifact downloads")
    
    def get_latest_successful_run(self) -> Optional[Dict[Any, Any]]:
        """Get the latest successful workflow run"""
        url = f"{self.base_url}/repos/{self.owner}/{self.repo}/actions/runs"
        params = {'status': 'completed', 'conclusion': 'success', 'per_page': 1}
        
        try:
            response = requests.get(url, headers=self.headers, params=params)
            response.raise_for_status()
            
            runs = response.json().get('workflow_runs', [])
            if runs:
                logger.info(f"Found latest successful run: {runs[0]['id']}")
                return runs[0]
                
        except requests.RequestException as e:
            logger.error(f"Failed to get workflow runs: {e}")
        
        return None
    
    def get_run_artifacts(self, run_id: int) -> list:
        """Get all artifacts for a specific workflow run"""
        url = f"{self.base_url}/repos/{self.owner}/{self.repo}/actions/runs/{run_id}/artifacts"
        
        try:
            response = requests.get(url, headers=self.headers)
            response.raise_for_status()
            
            artifacts = response.json().get('artifacts', [])
            # Filter out expired artifacts
            return [a for a in artifacts if not a.get('expired', True)]
            
        except requests.RequestException as e:
            logger.error(f"Failed to get artifacts for run {run_id}: {e}")
            return []
    
    def download_artifact(self, artifact_id: int, artifact_name: str) -> Optional[str]:
        """Download a specific artifact and extract it"""
        url = f"{self.base_url}/repos/{self.owner}/{self.repo}/actions/artifacts/{artifact_id}/zip"
        
        try:
            # Get download URL (GitHub returns a redirect)
            response = requests.get(url, headers=self.headers, allow_redirects=False)
            
            if response.status_code == 302:
                download_url = response.headers.get('Location')
                if not download_url:
                    logger.error(f"No redirect location for artifact {artifact_name}")
                    return None
                
                # Download the artifact
                logger.info(f"Downloading artifact: {artifact_name}")
                artifact_response = requests.get(download_url)
                artifact_response.raise_for_status()
                
                # Save the zip file temporarily
                zip_path = self.download_dir / f"{artifact_name}.zip"
                with open(zip_path, 'wb') as f:
                    f.write(artifact_response.content)
                
                # Extract the APK
                extracted_apk_path = self.extract_apk(zip_path, artifact_name)
                
                # Clean up zip file
                try:
                    os.remove(zip_path)
                except OSError:
                    pass
                
                return extracted_apk_path
                
            else:
                logger.error(f"Failed to get download URL for {artifact_name}: {response.status_code}")
                return None
                
        except requests.RequestException as e:
            logger.error(f"Failed to download artifact {artifact_name}: {e}")
            return None
    
    def extract_apk(self, zip_path: Path, artifact_name: str) -> Optional[str]:
        """Extract APK from zip file and save to downloads directory"""
        try:
            with zipfile.ZipFile(zip_path, 'r') as zip_ref:
                # Look for APK files in the zip
                apk_files = [f for f in zip_ref.namelist() if f.endswith('.apk')]
                
                if not apk_files:
                    logger.error(f"No APK files found in {zip_path}")
                    return None
                
                # Extract the first APK file
                apk_file = apk_files[0]
                apk_data = zip_ref.read(apk_file)
                
                # Save as app-debug.apk
                final_apk_path = self.download_dir / "app-debug.apk"
                with open(final_apk_path, 'wb') as f:
                    f.write(apk_data)
                
                logger.info(f"APK extracted to: {final_apk_path}")
                return str(final_apk_path)
                
        except zipfile.BadZipFile:
            logger.error(f"Invalid zip file: {zip_path}")
        except Exception as e:
            logger.error(f"Failed to extract APK from {zip_path}: {e}")
        
        return None
    
    def update_latest_apk(self) -> bool:
        """Download the latest APK from the most recent successful build"""
        logger.info("Checking for latest APK...")
        
        # Get latest successful run
        latest_run = self.get_latest_successful_run()
        if not latest_run:
            logger.warning("No successful workflow runs found")
            return False
        
        run_id = latest_run['id']
        
        # Get artifacts for this run
        artifacts = self.get_run_artifacts(run_id)
        if not artifacts:
            logger.warning(f"No artifacts found for run {run_id}")
            return False
        
        # Look for APK artifacts (typically named 'app-debug' or similar)
        apk_artifacts = [a for a in artifacts if 'debug' in a['name'].lower() or 'apk' in a['name'].lower()]
        
        if not apk_artifacts:
            # Fallback to first artifact
            apk_artifacts = artifacts[:1]
        
        if not apk_artifacts:
            logger.warning("No suitable APK artifacts found")
            return False
        
        # Download the first APK artifact
        artifact = apk_artifacts[0]
        downloaded_path = self.download_artifact(artifact['id'], artifact['name'])
        
        if downloaded_path:
            logger.info(f"Successfully updated APK: {downloaded_path}")
            return True
        else:
            logger.error("Failed to download APK artifact")
            return False
    
    def create_status_file(self, success: bool, message: str = ""):
        """Create a status file with the last update information"""
        status = {
            'last_update': datetime.now().isoformat(),
            'success': success,
            'message': message,
            'repository': f"{self.owner}/{self.repo}"
        }
        
        status_path = self.download_dir / "apk_status.json"
        with open(status_path, 'w') as f:
            json.dump(status, f, indent=2)

def main():
    """Main function to run the APK downloader"""
    # Configure repository (should match your GitHub repo)
    REPO_OWNER = "Levit513"
    REPO_NAME = "RF-Access"
    
    downloader = GitHubAPKDownloader(REPO_OWNER, REPO_NAME)
    
    try:
        success = downloader.update_latest_apk()
        if success:
            downloader.create_status_file(True, "APK downloaded successfully")
            print("✅ APK download completed successfully!")
        else:
            downloader.create_status_file(False, "Failed to download APK")
            print("❌ APK download failed")
            
    except Exception as e:
        logger.error(f"Unexpected error: {e}")
        downloader.create_status_file(False, f"Error: {str(e)}")
        print(f"💥 Error: {e}")

if __name__ == "__main__":
    main()