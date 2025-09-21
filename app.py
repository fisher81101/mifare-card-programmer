#!/usr/bin/env python3
"""
MIFARE Card Programming and Distribution System
Web application for creating, managing, and distributing MIFARE card programs
"""

from flask import Flask, render_template, request, jsonify, redirect, url_for, flash, session
from flask_sqlalchemy import SQLAlchemy
from flask_login import LoginManager, UserMixin, login_user, logout_user, login_required, current_user
from flask_wtf import FlaskForm
from flask_cors import CORS
from werkzeug.security import generate_password_hash, check_password_hash
from wtforms import StringField, PasswordField, TextAreaField, SelectField, HiddenField
from wtforms.validators import DataRequired, Email, Length
import os
import json
import qrcode
import io
import base64
from datetime import datetime, timedelta
import secrets
import hmac
import hashlib
import jwt
from mifare import CardReader, MifareUtils
from github_automation import GitHubAPKDownloader
import threading

# Global lock for APK download concurrency protection
apk_download_lock = threading.Lock()

app = Flask(__name__)

# Make datetime available in templates
@app.context_processor
def inject_datetime():
    return {'datetime': datetime}
app.config['SECRET_KEY'] = os.environ.get('SECRET_KEY', 'dev-secret-key-change-in-production')
app.config['SQLALCHEMY_DATABASE_URI'] = os.environ.get('DATABASE_URL', 'sqlite:///mifare_system.db')
app.config['SQLALCHEMY_TRACK_MODIFICATIONS'] = False

db = SQLAlchemy(app)
login_manager = LoginManager()
login_manager.init_app(app)
login_manager.login_view = 'login'
CORS(app)

# Database Models
class User(UserMixin, db.Model):
    id = db.Column(db.Integer, primary_key=True)
    username = db.Column(db.String(80), unique=True, nullable=False)
    email = db.Column(db.String(120), unique=True, nullable=False)
    password_hash = db.Column(db.String(255), nullable=False)
    is_admin = db.Column(db.Boolean, default=False)
    created_at = db.Column(db.DateTime, default=datetime.utcnow)
    programs_received = db.relationship('ProgramDistribution', backref='user', lazy=True)

class CardProgram(db.Model):
    id = db.Column(db.Integer, primary_key=True)
    name = db.Column(db.String(100), nullable=False)
    description = db.Column(db.Text)
    sector_data = db.Column(db.Text, nullable=False)  # JSON string of sector data
    created_by = db.Column(db.Integer, db.ForeignKey('user.id'), nullable=False)
    created_at = db.Column(db.DateTime, default=datetime.utcnow)
    is_active = db.Column(db.Boolean, default=True)
    distributions = db.relationship('ProgramDistribution', backref='program', lazy=True)

class ProgramDistribution(db.Model):
    id = db.Column(db.Integer, primary_key=True)
    program_id = db.Column(db.Integer, db.ForeignKey('card_program.id'), nullable=False)
    user_id = db.Column(db.Integer, db.ForeignKey('user.id'), nullable=False)
    access_token = db.Column(db.String(200), unique=True, nullable=False)
    expires_at = db.Column(db.DateTime, nullable=False)
    used_at = db.Column(db.DateTime)
    is_used = db.Column(db.Boolean, default=False)
    created_at = db.Column(db.DateTime, default=datetime.utcnow)

# Forms
class LoginForm(FlaskForm):
    username = StringField('Username', validators=[DataRequired()])
    password = PasswordField('Password', validators=[DataRequired()])

class RegisterForm(FlaskForm):
    username = StringField('Username', validators=[DataRequired(), Length(min=4, max=20)])
    email = StringField('Email', validators=[DataRequired(), Email()])
    password = PasswordField('Password', validators=[DataRequired(), Length(min=6)])

class CardProgramForm(FlaskForm):
    name = StringField('Program Name', validators=[DataRequired()])
    description = TextAreaField('Description')
    sector_data = TextAreaField('Sector Data (JSON)', validators=[DataRequired()])

class DistributeForm(FlaskForm):
    program_id = SelectField('Card Program', coerce=int, validators=[DataRequired()])
    user_id = SelectField('User', coerce=int, validators=[DataRequired()])

@login_manager.user_loader
def load_user(user_id):
    return User.query.get(int(user_id))

# Routes
@app.route('/')
def index():
    if current_user.is_authenticated:
        if current_user.is_admin:
            return redirect(url_for('admin_dashboard'))
        else:
            return redirect(url_for('user_dashboard'))
    return render_template('index.html')

@app.route('/login', methods=['GET', 'POST'])
def login():
    form = LoginForm()
    if form.validate_on_submit():
        user = User.query.filter_by(username=form.username.data).first()
        print(f"Login attempt for user: {form.username.data}")
        if user:
            print(f"User found: {user.username}, is_admin: {user.is_admin}")
            if check_password_hash(user.password_hash, form.password.data):
                print("Password verified successfully")
                login_user(user)
                return redirect(url_for('index'))
            else:
                print("Password verification failed")
        else:
            print("User not found")
        flash('Invalid username or password')
    return render_template('login.html', form=form)

@app.route('/register', methods=['GET', 'POST'])
def register():
    form = RegisterForm()
    if form.validate_on_submit():
        if User.query.filter_by(username=form.username.data).first():
            flash('Username already exists')
            return render_template('register.html', form=form)
        
        if User.query.filter_by(email=form.email.data).first():
            flash('Email already registered')
            return render_template('register.html', form=form)
        
        user = User(
            username=form.username.data,
            email=form.email.data,
            password_hash=generate_password_hash(form.password.data)
        )
        db.session.add(user)
        db.session.commit()
        flash('Registration successful')
        return redirect(url_for('login'))
    
    return render_template('register.html', form=form)

@app.route('/logout')
@login_required
def logout():
    logout_user()
    return redirect(url_for('index'))

@app.route('/admin')
@login_required
def admin_dashboard():
    if not current_user.is_admin:
        flash('Access denied')
        return redirect(url_for('user_dashboard'))
    
    programs = CardProgram.query.filter_by(created_by=current_user.id).all()
    users = User.query.filter_by(is_admin=False).all()
    distributions = ProgramDistribution.query.join(CardProgram).filter(
        CardProgram.created_by == current_user.id
    ).all()
    
    return render_template('admin_dashboard.html', 
                         programs=programs, users=users, distributions=distributions)

@app.route('/user')
@login_required
def user_dashboard():
    distributions = ProgramDistribution.query.filter_by(user_id=current_user.id).all()
    return render_template('user_dashboard.html', distributions=distributions)

@app.route('/create_program', methods=['GET', 'POST'])
@login_required
def create_program():
    if not current_user.is_admin:
        flash('Access denied')
        return redirect(url_for('user_dashboard'))
    
    form = CardProgramForm()
    if form.validate_on_submit():
        try:
            # Validate JSON format
            sector_data = form.sector_data.data
            if sector_data:
                json.loads(sector_data)
            
            program = CardProgram(
                name=form.name.data,
                description=form.description.data,
                sector_data=form.sector_data.data,
                created_by=current_user.id
            )
            db.session.add(program)
            db.session.commit()
            flash('Card program created successfully')
            return redirect(url_for('admin_dashboard'))
        except json.JSONDecodeError:
            flash('Invalid JSON format in sector data')
    
    return render_template('create_program.html', form=form)

@app.route('/distribute', methods=['GET', 'POST'])
@login_required
def distribute_program():
    if not current_user.is_admin:
        flash('Access denied')
        return redirect(url_for('user_dashboard'))
    
    form = DistributeForm()
    form.program_id.choices = [(p.id, p.name) for p in CardProgram.query.filter_by(created_by=current_user.id).all()]
    form.user_id.choices = [(u.id, u.username) for u in User.query.filter_by(is_admin=False).all()]
    
    if form.validate_on_submit():
        # Generate secure access token
        access_token = secrets.token_urlsafe(32)
        expires_at = datetime.utcnow() + timedelta(hours=168)  # 7-day expiry for testing
        
        # Debug logging
        print(f"Creating distribution: token={access_token[:8]}..., expires_at={expires_at}")
        
        distribution = ProgramDistribution(
            program_id=form.program_id.data,
            user_id=form.user_id.data,
            access_token=access_token,
            expires_at=expires_at
        )
        db.session.add(distribution)
        db.session.commit()
        
        # Verify token was saved
        saved_dist = ProgramDistribution.query.filter_by(access_token=access_token).first()
        print(f"Token saved: {saved_dist is not None}, expires: {saved_dist.expires_at if saved_dist else 'None'}")
        
        flash(f'Program distributed successfully. Link expires: {expires_at.strftime("%Y-%m-%d %H:%M UTC")}')
        return redirect(url_for('admin_dashboard'))
    
    return render_template('distribute.html', form=form)

@app.route('/mobile_redirect')
def mobile_redirect():
    token = request.args.get('token')
    distribution = ProgramDistribution.query.filter_by(access_token=token).first()
    
    if not distribution:
        return render_template('error.html', message='Invalid program link')
    
    return render_template('mobile_redirect.html', token=token, distribution=distribution)

@app.route('/debug_mobile_redirect')
def debug_mobile_redirect():
    return render_template('debug_mobile_redirect.html')

@app.route('/program/<token>')
def receive_program(token):
    # Debug logging
    print(f"Received token: {token[:8]}... at {datetime.utcnow()}")
    
    distribution = ProgramDistribution.query.filter_by(access_token=token).first()
    
    if not distribution:
        print(f"Token not found in database: {token[:8]}...")
        return render_template('error.html', message='Invalid or expired program link')
    
    print(f"Found distribution: expires={distribution.expires_at}, now={datetime.utcnow()}, used={distribution.is_used}")
    
    if distribution.expires_at < datetime.utcnow():
        print(f"Token expired: {distribution.expires_at} < {datetime.utcnow()}")
        return render_template('error.html', message='Program link has expired')
    
    # Check if programming was already completed successfully
    if distribution.is_used:
        return render_template('error.html', message='This program has already been successfully programmed. Request a new distribution to program again.')
    
    # Mobile detection and app redirect (unless forced to use web)
    force_web = request.args.get('force_web') == '1'
    user_agent = request.headers.get('User-Agent', '').lower()
    is_mobile = any(device in user_agent for device in ['android', 'iphone', 'ipad', 'mobile', 'webos', 'blackberry'])
    
    if is_mobile and not force_web:
        # Extract username from distribution or use default
        username = distribution.user.username if distribution.user else 'user_from_web'
        
        # Create app deep link with token as card data
        app_url = f"rfaccess://open?username={username}&cardData={token}&action=program"
        
        # Return redirect template that tries app first, then falls back to web
        return render_template('mobile_redirect.html', 
                             app_url=app_url, 
                             token=token,
                             distribution=distribution, 
                             program=distribution.program)
    
    program = distribution.program
    return render_template('receive_program.html', 
                         distribution=distribution, program=program)

@app.route('/api/programming_success/<token>', methods=['POST'])
def mark_programming_success(token):
    """Mark a distribution as successfully programmed"""
    try:
        distribution = ProgramDistribution.query.filter_by(access_token=token).first()
        
        if not distribution:
            return jsonify({'error': 'Token not found'}), 404
        
        if distribution.expires_at < datetime.utcnow():
            return jsonify({'error': 'Token expired'}), 403
            
        if distribution.is_used:
            return jsonify({'error': 'Already marked as used'}), 403
        
        # Mark as successfully programmed
        distribution.is_used = True
        distribution.used_at = datetime.utcnow()
        db.session.commit()
        
        return jsonify({'success': True, 'message': 'Programming marked as successful'})
        
    except Exception as e:
        return jsonify({'error': f'Database error: {str(e)}'}), 500

@app.route('/api/program_data/<token>')
def get_program_data(token):
    try:
        distribution = ProgramDistribution.query.filter_by(access_token=token).first()
        
        if not distribution:
            return jsonify({'error': 'Token not found - program may have been lost due to database restart'}), 404
        
        if distribution.expires_at < datetime.utcnow():
            return jsonify({'error': 'Token expired'}), 403
            
        # Check if programming was already completed successfully
        if distribution.is_used:
            return jsonify({'error': 'This program has already been successfully programmed. Request a new distribution to program again.'}), 403
        
        program = distribution.program
        if not program:
            return jsonify({'error': 'Program not found - data may have been lost'}), 404
        
        # Update last accessed time but don't mark as used yet (wait for success confirmation)
        distribution.used_at = datetime.utcnow()
        db.session.commit()
        
        sector_data = json.loads(program.sector_data)
        
        return jsonify({
            'program_name': program.name,
            'sector_data': sector_data,
            'timestamp': datetime.utcnow().isoformat()
        })
        
    except Exception as e:
        return jsonify({'error': f'Database error: {str(e)}'}), 500

# Android API Endpoints
@app.route('/api/test', methods=['GET'])
def api_test():
    """Test endpoint for Android app connectivity"""
    return jsonify({
        'success': True,
        'message': 'MIFARE Web API is running',
        'timestamp': datetime.utcnow().isoformat(),
        'version': '1.0.0'
    })

@app.route('/api/android/generate-config', methods=['POST'])
def android_generate_config():
    """Generate card configuration for Android app"""
    try:
        # Check API key/authorization (basic implementation)
        auth_header = request.headers.get('Authorization')
        if not auth_header or not auth_header.startswith('Bearer '):
            return jsonify({'success': False, 'message': 'Missing or invalid authorization'}), 401
        
        data = request.get_json()
        if not data:
            return jsonify({'success': False, 'message': 'No JSON data provided'}), 400
        
        # Validate required fields
        required_fields = ['userId', 'doors', 'startDate', 'endDate']
        for field in required_fields:
            if field not in data:
                return jsonify({'success': False, 'message': f'Missing field: {field}'}), 400
        
        # Generate sample Salto-compatible data structure
        config_data = {
            'userId': data['userId'],
            'doors': data['doors'].split(',') if isinstance(data['doors'], str) else data['doors'],
            'startDate': data['startDate'],
            'endDate': data['endDate'],
            'notes': data.get('notes', ''),
            'accessLevel': data.get('accessLevel', 'guest'),
            'cardType': 'mifare_classic_1k'
        }
        
        # Convert to base64 encoded byte data (simulated)
        import base64
        config_json = json.dumps(config_data)
        encoded_data = base64.b64encode(config_json.encode('utf-8')).decode('utf-8')
        
        return jsonify({
            'success': True,
            'message': 'Configuration generated successfully',
            'cardData': encoded_data,
            'timestamp': datetime.utcnow().isoformat()
        })
        
    except Exception as e:
        return jsonify({'success': False, 'message': f'Server error: {str(e)}'}), 500

@app.route('/api/android/programming-result', methods=['POST'])
def android_programming_result():
    """Receive programming result from Android app"""
    try:
        auth_header = request.headers.get('Authorization')
        if not auth_header or not auth_header.startswith('Bearer '):
            return jsonify({'success': False, 'message': 'Missing or invalid authorization'}), 401
        
        data = request.get_json()
        if not data:
            return jsonify({'success': False, 'message': 'No JSON data provided'}), 400
        
        # Log the programming result
        print(f"Android Programming Result: User {data.get('userId')}, Card {data.get('cardUid')}, Success: {data.get('success')}")
        
        if not data.get('success', False):
            print(f"Programming Error: {data.get('errorMessage')}")
        
        # Here you could store the result in a database table if needed
        # For now, we'll just acknowledge receipt
        
        return jsonify({
            'success': True,
            'message': 'Programming result received',
            'timestamp': datetime.utcnow().isoformat()
        })
        
    except Exception as e:
        return jsonify({'success': False, 'message': f'Server error: {str(e)}'}), 500

@app.route('/api/android/programs', methods=['GET'])
def android_get_programs():
    """Get available card programs for Android app"""
    try:
        auth_header = request.headers.get('Authorization')
        if not auth_header or not auth_header.startswith('Bearer '):
            return jsonify({'success': False, 'message': 'Missing or invalid authorization'}), 401
        
        # Get all active programs
        programs = CardProgram.query.filter_by(is_active=True).all()
        
        program_list = []
        for program in programs:
            program_data = {
                'id': program.id,
                'name': program.name,
                'description': program.description,
                'created_at': program.created_at.isoformat(),
                'sector_data_size': len(program.sector_data) if program.sector_data else 0
            }
            program_list.append(program_data)
        
        return jsonify({
            'success': True,
            'message': f'Found {len(program_list)} programs',
            'programs': program_list,
            'timestamp': datetime.utcnow().isoformat()
        })
        
    except Exception as e:
        return jsonify({'success': False, 'message': f'Server error: {str(e)}'}), 500

@app.route('/api/scan_card')
@login_required
def scan_card():
    """API endpoint to scan for MIFARE cards"""
    try:
        reader = CardReader()
        cards = reader.scan_cards()
        return jsonify({'success': True, 'cards': cards})
    except Exception as e:
        return jsonify({'success': False, 'error': str(e)})

@app.route('/sector_editor')
@login_required
def sector_editor():
    """Interactive sector editor for MIFARE Classic cards"""
    if not current_user.is_admin:
        flash('Access denied')
        return redirect(url_for('user_dashboard'))
    
    return render_template('sector_editor.html')

@app.route('/users')
@login_required
def manage_users():
    """User management page"""
    if not current_user.is_admin:
        flash('Access denied. Admin privileges required.', 'error')
        return redirect(url_for('dashboard'))
    
    users = User.query.all()
    return render_template('manage_users.html', users=users)

@app.route('/manage_programs')
@login_required
def manage_programs():
    if not current_user.is_admin:
        flash('Access denied. Admin privileges required.', 'error')
        return redirect(url_for('dashboard'))
    
    programs = CardProgram.query.all()
    return render_template('manage_programs.html', programs=programs)

@app.route('/redistribute_program/<int:program_id>', methods=['GET', 'POST'])
@login_required
def redistribute_program(program_id):
    if not current_user.is_admin:
        flash('Access denied. Admin privileges required.', 'error')
        return redirect(url_for('dashboard'))
    
    program = CardProgram.query.get_or_404(program_id)
    
    if request.method == 'POST':
        user_id = request.form.get('user_id')
        user = User.query.get(user_id)
        
        if not user:
            flash('User not found', 'error')
            return redirect(url_for('redistribute_program', program_id=program_id))
        
        # Create new distribution for existing program
        access_token = secrets.token_urlsafe(32)
        expires_at = datetime.utcnow() + timedelta(hours=24)
        
        distribution = ProgramDistribution(
            program_id=program.id,
            user_id=user.id,
            access_token=access_token,
            expires_at=expires_at,
            is_used=False
        )
        
        db.session.add(distribution)
        db.session.commit()
        
        flash(f'Program "{program.name}" redistributed to {user.username}', 'success')
        return redirect(url_for('manage_programs'))
    
    users = User.query.filter_by(is_admin=False).all()
    return render_template('redistribute_program.html', program=program, users=users)

@app.route('/create_user', methods=['GET', 'POST'])
@login_required
def create_user():
    """Create new user"""
    if not current_user.is_admin:
        flash('Access denied. Admin privileges required.', 'error')
        return redirect(url_for('dashboard'))
        return redirect(url_for('user_dashboard'))
    
    if request.method == 'POST':
        username = request.form.get('username')
        email = request.form.get('email')
        password = request.form.get('password')
        
        # Check if user already exists
        if User.query.filter_by(username=username).first():
            flash('Username already exists')
            return render_template('create_user.html')
        
        if User.query.filter_by(email=email).first():
            flash('Email already exists')
            return render_template('create_user.html')
        
        # Create new user
        new_user = User(
            username=username,
            email=email,
            password_hash=generate_password_hash(password),
            is_admin=False
        )
        
        db.session.add(new_user)
        db.session.commit()
        
        flash(f'User {username} created successfully')
        return redirect(url_for('manage_users'))
    
    return render_template('create_user.html')

# APK Automation Endpoints
@app.route('/webhook/github', methods=['POST'])
def github_webhook():
    """Handle GitHub webhook notifications for automated APK downloads"""
    try:
        # Require webhook secret to be configured
        webhook_secret = os.environ.get('WEBHOOK_SECRET')
        if not webhook_secret:
            print("❌ WEBHOOK_SECRET not configured - rejecting webhook")
            return jsonify({'error': 'Webhook secret not configured'}), 500
        
        # MANDATORY signature verification for security
        signature = request.headers.get('X-Hub-Signature-256')
        if not signature:
            print("❌ Missing webhook signature - rejecting unauthenticated request")
            return jsonify({'error': 'Signature required'}), 401
        
        expected_signature = 'sha256=' + hmac.new(
            webhook_secret.encode(), request.data, hashlib.sha256
        ).hexdigest()
        
        if not secrets.compare_digest(signature, expected_signature):
            print("❌ Invalid webhook signature - rejecting unauthorized request")  
            return jsonify({'error': 'Invalid signature'}), 401
        
        # Verify GitHub event type
        event_type = request.headers.get('X-GitHub-Event')
        if event_type != 'workflow_run':
            return jsonify({'status': 'ignored - not a workflow_run event'}), 200
        
        payload = request.json
        
        # Verify repository to prevent cross-repo triggers
        repository = payload.get('repository', {})
        repo_full_name = repository.get('full_name', '')
        if repo_full_name != 'Levit513/RF-Access':
            print(f"❌ Ignoring webhook from different repository: {repo_full_name}")
            return jsonify({'status': 'ignored - wrong repository'}), 200
        
        # Check if it's a workflow_run completion event with success
        if (payload.get('action') == 'completed' and 
            payload.get('workflow_run', {}).get('conclusion') == 'success'):
            
            print(f"🎯 GitHub webhook: Workflow completed successfully for {repo_full_name}")
            
            # Check for required GitHub token before starting
            if not os.environ.get('GITHUB_TOKEN'):
                error_msg = "GITHUB_TOKEN not configured - cannot download APK artifacts"
                print(f"❌ {error_msg}")
                return jsonify({'error': error_msg}), 503
            
            # Check concurrency protection
            if apk_download_lock.locked():
                print("⚠️ APK download already in progress - skipping duplicate trigger")
                return jsonify({'status': 'download already in progress'}), 200
            
            # Trigger APK download in background with concurrency protection
            threading.Thread(target=download_latest_apk_async, daemon=True).start()
            return jsonify({'status': 'APK download triggered'}), 200
        
        return jsonify({'status': 'ignored'}), 200
        
    except Exception as e:
        print(f"Webhook error: {e}")
        return jsonify({'error': str(e)}), 500

@app.route('/admin/update-apk', methods=['POST'])
@login_required
def update_apk():
    """Manually trigger APK download (admin only)"""
    if not current_user.is_admin:
        return jsonify({'error': 'Admin access required'}), 403
    
    try:
        # Check for required GitHub token
        if not os.environ.get('GITHUB_TOKEN'):
            return jsonify({'error': 'GITHUB_TOKEN not configured - cannot download APK artifacts'}), 503
        
        # Check concurrency protection  
        if apk_download_lock.locked():
            return jsonify({'status': 'APK download already in progress'}), 200
            
        threading.Thread(target=download_latest_apk_async, daemon=True).start()
        return jsonify({'status': 'APK download started'}), 200
        
    except Exception as e:
        return jsonify({'error': str(e)}), 500

@app.route('/api/apk-status')
def apk_status():
    """Get current APK download status"""
    try:
        status_file = os.path.join('static', 'downloads', 'apk_status.json')
        
        if os.path.exists(status_file):
            with open(status_file, 'r') as f:
                status = json.load(f)
            return jsonify(status)
        else:
            return jsonify({
                'last_update': None,
                'success': False,
                'message': 'No APK downloads attempted yet',
                'repository': 'Levit513/RF-Access'
            })
    
    except Exception as e:
        return jsonify({'error': str(e)}), 500

def download_latest_apk_async():
    """Background task to download latest APK with concurrency protection"""
    # Acquire lock to prevent concurrent downloads
    if not apk_download_lock.acquire(blocking=False):
        print("⚠️ APK download already in progress - skipping")
        return
    
    try:
        print("🔄 Starting automatic APK download...")
        
        # Verify GitHub token is configured
        github_token = os.environ.get('GITHUB_TOKEN')
        if not github_token:
            error_msg = "GITHUB_TOKEN not configured - cannot download APK artifacts"
            print(f"❌ {error_msg}")
            
            # Create downloader just for status file
            downloader = GitHubAPKDownloader('Levit513', 'RF-Access')
            downloader.create_status_file(False, error_msg)
            return
        
        # Initialize downloader
        downloader = GitHubAPKDownloader('Levit513', 'RF-Access')
        
        # Download latest APK
        success = downloader.update_latest_apk()
        
        if success:
            print("✅ APK download completed successfully!")
            downloader.create_status_file(True, "APK downloaded successfully via automation")
        else:
            print("❌ APK download failed")
            downloader.create_status_file(False, "APK download failed - check GitHub token and repository access")
            
    except Exception as e:
        print(f"💥 APK download error: {e}")
        # Initialize downloader just for status file creation
        try:
            downloader = GitHubAPKDownloader('Levit513', 'RF-Access')
            downloader.create_status_file(False, f"APK download error: {str(e)}")
        except:
            pass
    finally:
        # Always release the lock
        apk_download_lock.release()

def create_admin_user():
    """Create default admin user if none exists"""
    # Check if admin user exists
    admin = User.query.filter_by(username='admin').first()
    if admin:
        # Update existing admin user with correct password
        admin.password_hash = generate_password_hash('admin123')
        admin.is_admin = True
        db.session.commit()
        print("Admin user updated: admin/admin123")
    else:
        # Create new admin user
        admin = User(
            username='admin',
            email='admin@example.com',
            password_hash=generate_password_hash('admin123'),
            is_admin=True
        )
        db.session.add(admin)
        db.session.commit()
        print("Default admin user created: admin/admin123")
    
    # Verify the password works
    test_user = User.query.filter_by(username='admin').first()
    if test_user and check_password_hash(test_user.password_hash, 'admin123'):
        print("✅ Admin password verification successful")
    else:
        print("❌ Admin password verification failed")

if __name__ == '__main__':
    with app.app_context():
        db.create_all()
        create_admin_user()
    
    app.run(debug=True, host='0.0.0.0', port=5000)
