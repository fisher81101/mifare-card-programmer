from flask import Flask, render_template, request, redirect, url_for, flash, session, jsonify, send_file
from flask_sqlalchemy import SQLAlchemy
from flask_login import LoginManager, UserMixin, login_user, logout_user, login_required, current_user
from flask_wtf import FlaskForm
from flask_cors import CORS
from wtforms import StringField, PasswordField, TextAreaField, SelectField, SubmitField
from wtforms.validators import DataRequired, Length, Email
from werkzeug.security import generate_password_hash, check_password_hash
import os
from datetime import datetime, timedelta
import secrets
import jwt
import qrcode
from io import BytesIO
import base64
import json
import logging
import psycopg2
import time
import requests
from functools import wraps

# Initialize Flask app
app = Flask(__name__)

# Configure logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

# Configuration
app.config['SECRET_KEY'] = os.environ.get('SECRET_KEY', 'dev-secret-key-change-in-production')
app.config['SQLALCHEMY_DATABASE_URI'] = os.environ.get('DATABASE_URL', 'sqlite:///mifare_system.db')
app.config['SQLALCHEMY_TRACK_MODIFICATIONS'] = False
app.config['SQLALCHEMY_ENGINE_OPTIONS'] = {
    'pool_pre_ping': True,
    'pool_recycle': 300,
    'connect_args': {"sslmode": "require"}
}

# Initialize extensions
db = SQLAlchemy(app)
login_manager = LoginManager()
login_manager.init_app(app)
login_manager.login_view = 'login'

# CORS configuration
CORS(app, origins=['https://app.513solutions.com', 'https://*.replit.dev'], 
     allow_headers=['Content-Type', 'Authorization', 'X-Requested-With'],
     methods=['GET', 'POST', 'PUT', 'DELETE', 'OPTIONS'],
     supports_credentials=True)

# Database retry decorator
def retry_db_operation(max_attempts=3, delay=2):
    def decorator(func):
        @wraps(func)
        def wrapper(*args, **kwargs):
            attempts = 0
            while attempts < max_attempts:
                try:
                    return func(*args, **kwargs)
                except Exception as e:
                    attempts += 1
                    if attempts == max_attempts:
                        logger.error(f"Database operation failed after {max_attempts} attempts: {e}")
                        raise e
                    logger.warning(f"Database error: {e}. Retrying {attempts}/{max_attempts}...")
                    time.sleep(delay)
                    # Try to close any lingering connections
                    try:
                        db.session.close()
                    except:
                        pass
            return wrapper
        return wrapper
    return decorator

# Models
class User(UserMixin, db.Model):
    id = db.Column(db.Integer, primary_key=True)
    username = db.Column(db.String(80), unique=True, nullable=False)
    email = db.Column(db.String(120), unique=True, nullable=False)
    password_hash = db.Column(db.String(128), nullable=False)
    is_admin = db.Column(db.Boolean, default=False)
    created_at = db.Column(db.DateTime, default=datetime.utcnow)

class CardProgram(db.Model):
    id = db.Column(db.Integer, primary_key=True)
    name = db.Column(db.String(100), nullable=False)
    description = db.Column(db.Text)
    sector_data = db.Column(db.Text, nullable=False)  # JSON string
    created_by = db.Column(db.Integer, db.ForeignKey('user.id'), nullable=False)
    created_at = db.Column(db.DateTime, default=datetime.utcnow)

class ProgramDistribution(db.Model):
    id = db.Column(db.Integer, primary_key=True)
    program_id = db.Column(db.Integer, db.ForeignKey('card_program.id'), nullable=False)
    user_id = db.Column(db.Integer, db.ForeignKey('user.id'), nullable=False)
    access_token = db.Column(db.String(200), unique=True, nullable=False)  # Match database column name
    expires_at = db.Column(db.DateTime, nullable=False)
    used_at = db.Column(db.DateTime)  # Match database column name
    is_used = db.Column(db.Boolean, default=False)  # Match database column name  
    created_at = db.Column(db.DateTime, default=datetime.utcnow)
    
    # Relationships
    program = db.relationship('CardProgram', backref='distributions')
    user = db.relationship('User', backref='distributions')

class ProgrammingLog(db.Model):
    """Enterprise audit trail for card programming operations"""
    id = db.Column(db.Integer, primary_key=True)
    distribution_id = db.Column(db.Integer, db.ForeignKey('program_distribution.id'), nullable=False)
    user_id = db.Column(db.String(100), nullable=True)  # From Android app
    card_uid = db.Column(db.String(50), nullable=True)
    success = db.Column(db.Boolean, nullable=False)
    error_message = db.Column(db.Text, nullable=True)
    programming_timestamp = db.Column(db.DateTime, nullable=True)  # From Android
    logged_at = db.Column(db.DateTime, default=datetime.utcnow, nullable=False)
    ip_address = db.Column(db.String(45), nullable=True)  # IPv4/IPv6
    user_agent = db.Column(db.String(500), nullable=True)
    
    # Relationships
    distribution = db.relationship('ProgramDistribution', backref='programming_logs')

# Forms
class LoginForm(FlaskForm):
    username = StringField('Username', validators=[DataRequired(), Length(min=4, max=20)])
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

class AccessLinkForm(FlaskForm):
    distribution_link = StringField('Distribution Link', 
                                  validators=[DataRequired()], 
                                  render_kw={"placeholder": "Paste your distribution link here..."})
    submit = SubmitField('Access Program')

@login_manager.user_loader
def load_user(user_id):
    return User.query.get(int(user_id))

# Log requests
@app.before_request
def log_request():
    logger.debug(f"🌐 Request: {request.method} {request.url}")
    if request.method == 'POST':
        logger.debug(f"🌐 Form Data: {dict(request.form)}")

# Cache control headers
@app.after_request
def add_header(response):
    response.headers['Cache-Control'] = 'no-store, no-cache, must-revalidate, max-age=0'
    response.headers['Pragma'] = 'no-cache'
    response.headers['Expires'] = '0'
    return response

# Alternative database query function using psycopg2 directly
def query_user_direct(username):
    """Direct psycopg2 query as fallback for SQLAlchemy issues"""
    try:
        DATABASE_URL = os.environ.get('DATABASE_URL')
        conn = psycopg2.connect(DATABASE_URL)
        cursor = conn.cursor()
        cursor.execute('SELECT id, username, email, password_hash, is_admin FROM "user" WHERE username = %s', (username,))
        result = cursor.fetchone()
        conn.close()
        
        if result:
            user_data = {
                'id': result[0],
                'username': result[1], 
                'email': result[2],
                'password_hash': result[3],
                'is_admin': result[4]
            }
            return user_data
        return None
    except Exception as e:
        logger.error(f"Direct database query failed: {e}")
        return None

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
@retry_db_operation()
def login():
    if current_user.is_authenticated:
        logger.debug("User already authenticated, redirecting to index")
        return redirect(url_for('index'))
    
    form = LoginForm()
    
    if request.method == 'POST':
        
        if form.validate_on_submit():
            username = form.username.data
            password = form.password.data
            
            try:
                # First try SQLAlchemy
                user = User.query.filter_by(username=username).first()
            except Exception as e:
                logger.warning(f"SQLAlchemy query failed: {e}. Trying direct query...")
                # Fallback to direct psycopg2 query
                user_data = query_user_direct(username)
                if user_data:
                    # Create a temporary User object
                    user = User()
                    user.id = user_data['id']
                    user.username = user_data['username']
                    user.email = user_data['email'] 
                    user.password_hash = user_data['password_hash']
                    user.is_admin = user_data['is_admin']
                else:
                    user = None
            
            if user and check_password_hash(user.password_hash, password):
                logger.info(f"Login successful for user: {username}")
                login_user(user)
                next_page = request.args.get('next')
                return redirect(next_page) if next_page else redirect(url_for('index'))
            else:
                flash('Invalid username or password', 'danger')
        else:
            flash('Please check your input', 'danger')
    
    return render_template('login.html', form=form)

@app.route('/register', methods=['GET', 'POST'])
def register():
    # Allow admins to create new users, but redirect regular authenticated users
    if current_user.is_authenticated and not current_user.is_admin:
        return redirect(url_for('index'))
    
    form = RegisterForm()
    if form.validate_on_submit():
        if User.query.filter_by(username=form.username.data).first():
            flash('Username already exists', 'danger')
            return render_template('register.html', form=form)
        
        if User.query.filter_by(email=form.email.data).first():
            flash('Email already registered', 'danger')
            return render_template('register.html', form=form)
        
        user = User(
            username=form.username.data,
            email=form.email.data,
            password_hash=generate_password_hash(form.password.data)
        )
        db.session.add(user)
        db.session.commit()
        flash('Registration successful! Please log in.', 'success')
        return redirect(url_for('login'))
    
    return render_template('register.html', form=form)

@app.route('/logout')
@login_required
def logout():
    logout_user()
    flash('You have been logged out.', 'info')
    return redirect(url_for('index'))

@app.route('/admin')
@login_required
def admin_dashboard():
    if not current_user.is_admin:
        flash('Access denied. Administrator privileges required.', 'danger')
        return redirect(url_for('index'))
    
    programs = CardProgram.query.filter_by(created_by=current_user.id).all()
    users = User.query.filter_by(is_admin=False).all()
    distributions = ProgramDistribution.query.join(CardProgram).filter(
        CardProgram.created_by == current_user.id
    ).all()
    
    return render_template('admin_dashboard.html', 
                         programs=programs, 
                         users=users, 
                         distributions=distributions,
                         datetime=datetime)

@app.route('/dashboard')
@login_required
def user_dashboard():
    if current_user.is_admin:
        return redirect(url_for('admin_dashboard'))
    
    distributions = ProgramDistribution.query.filter_by(user_id=current_user.id).all()
    return render_template('user_dashboard.html', distributions=distributions)

@app.route('/access-link', methods=['GET', 'POST'])
@login_required
def access_link():
    """Allow users to input and access distribution links"""
    form = AccessLinkForm()
    
    if form.validate_on_submit():
        # Extract token from the distribution link
        distribution_link = form.distribution_link.data.strip()
        
        # Handle different URL formats
        # Full URL: https://domain.com/program/TOKEN
        # Just token: TOKEN
        if '/program/' in distribution_link:
            token = distribution_link.split('/program/')[-1]
        else:
            token = distribution_link
        
        # Validate token format (basic check)
        if not token or len(token) < 20:
            flash('Invalid distribution link format', 'danger')
            return render_template('access_link.html', form=form)
        
        # Check if distribution exists and is valid
        distribution = ProgramDistribution.query.filter_by(access_token=token).first()
        
        if not distribution:
            flash('Invalid distribution link - link not found', 'danger')
            return render_template('access_link.html', form=form)
        
        if distribution.expires_at < datetime.utcnow():
            flash('This distribution link has expired', 'danger')
            return render_template('access_link.html', form=form)
        
        if distribution.is_used:
            flash('This distribution link has already been used', 'warning')
            return render_template('access_link.html', form=form)
        
        # Redirect to the program access page
        return redirect(url_for('program_access', token=token))
    
    return render_template('access_link.html', form=form)

@app.route('/create_program', methods=['GET', 'POST'])
@login_required
def create_program():
    if not current_user.is_admin:
        flash('Access denied. Administrator privileges required.', 'danger')
        return redirect(url_for('index'))
    
    form = CardProgramForm()
    
    # Pre-populate with sector data from session if available
    if 'sector_data' in session and request.method == 'GET':
        form.sector_data.data = session['sector_data']
        session.pop('sector_data', None)  # Remove after use
        flash('Sector data loaded from editor!', 'info')
    
    if form.validate_on_submit():
        try:
            # Validate JSON
            json.loads(form.sector_data.data)
            
            program = CardProgram(
                name=form.name.data,
                description=form.description.data,
                sector_data=form.sector_data.data,
                created_by=current_user.id
            )
            db.session.add(program)
            db.session.commit()
            flash('Card program created successfully!', 'success')
            return redirect(url_for('admin_dashboard'))
        except json.JSONDecodeError:
            flash('Invalid JSON format in sector data', 'danger')
    
    return render_template('create_program.html', form=form)

@app.route('/sector_editor', methods=['GET', 'POST'])
@login_required
def sector_editor():
    if not current_user.is_admin:
        flash('Access denied. Administrator privileges required.', 'danger')
        return redirect(url_for('index'))
    
    if request.method == 'POST':
        try:
            # Process sector data from form
            card_type = request.form.get('card_type', '1K')
            sector_data = {}
            
            # Parse sector data from form
            max_sectors = 16 if card_type == '1K' else 40
            
            for sector in range(max_sectors):
                sector_data[str(sector)] = {
                    'blocks': [],
                    'keys': {
                        'keyA': request.form.get(f'sector_{sector}_keyA', 'FFFFFFFFFFFF'),
                        'keyB': request.form.get(f'sector_{sector}_keyB', 'FFFFFFFFFFFF')
                    }
                }
                
                # Get blocks for this sector
                blocks_per_sector = 4 if sector < 32 else 16
                for block in range(blocks_per_sector):
                    block_data = request.form.get(f'sector_{sector}_block_{block}', '00' * 16)
                    # Validate hex data
                    if len(block_data.replace(' ', '')) == 32:
                        sector_data[str(sector)]['blocks'].append(block_data)
                    else:
                        sector_data[str(sector)]['blocks'].append('00' * 16)
            
            # Save as JSON and redirect to create program
            session['sector_data'] = json.dumps(sector_data)
            flash('Sector data configured successfully! Fill in program details.', 'success')
            return redirect(url_for('create_program'))
            
        except Exception as e:
            flash(f'Error processing sector data: {str(e)}', 'danger')
    
    return render_template('sector_editor.html')

@app.route('/distribute', methods=['GET', 'POST'])
@login_required
def distribute():
    if not current_user.is_admin:
        flash('Access denied. Administrator privileges required.', 'danger')
        return redirect(url_for('index'))
    
    form = DistributeForm()
    form.program_id.choices = [(p.id, p.name) for p in CardProgram.query.filter_by(created_by=current_user.id).all()]
    form.user_id.choices = [(u.id, u.username) for u in User.query.filter_by(is_admin=False).all()]
    
    if form.validate_on_submit():
        access_token = secrets.token_urlsafe(32)
        expires_at = datetime.utcnow() + timedelta(hours=24)
        
        distribution = ProgramDistribution(
            program_id=form.program_id.data,
            user_id=form.user_id.data,
            access_token=access_token,
            expires_at=expires_at
        )
        db.session.add(distribution)
        db.session.commit()
        flash('Program distributed successfully!', 'success')
        return redirect(url_for('admin_dashboard'))
    
    return render_template('distribute.html', form=form)

@app.route('/program/<token>')
def program_access(token):
    distribution = ProgramDistribution.query.filter_by(access_token=token).first_or_404()
    
    if distribution.expires_at < datetime.utcnow():
        flash('This link has expired', 'danger')
        return redirect(url_for('index'))
    
    if distribution.is_used:
        flash('This program has already been used', 'warning')
        return redirect(url_for('index'))
    
    program = CardProgram.query.get(distribution.program_id)
    return render_template('program_access.html', program=program, distribution=distribution)

# Android API Endpoints
@app.route('/api/test', methods=['GET'])
def api_test():
    """Test endpoint for Android app connectivity"""
    return jsonify({
        'success': True,
        'message': 'Backend connection successful',
        'timestamp': datetime.utcnow().isoformat(),
        'version': '1.0'
    })

@app.route('/api/android/programs', methods=['GET'])
def api_android_programs():
    """Get available programs for Android app"""
    try:
        # Get auth token from header
        auth_header = request.headers.get('Authorization', '')
        if not auth_header.startswith('Bearer '):
            return jsonify({'success': False, 'message': 'Invalid authorization header'}), 401
        
        token = auth_header.replace('Bearer ', '')
        
        # Find distribution by token
        distribution = ProgramDistribution.query.filter_by(access_token=token).first()
        if not distribution:
            return jsonify({'success': False, 'message': 'Invalid access token'}), 401
        
        if distribution.expires_at < datetime.utcnow():
            return jsonify({'success': False, 'message': 'Access token expired'}), 401
        
        if distribution.is_used:
            return jsonify({'success': False, 'message': 'Access token already used'}), 410
        
        # Get the program
        program = CardProgram.query.get(distribution.program_id)
        if not program:
            return jsonify({'success': False, 'message': 'Program not found'}), 404
        
        # Return program data
        program_data = {
            'id': program.id,
            'name': program.name,
            'description': program.description,
            'sector_data': json.loads(program.sector_data),
            'created_at': program.created_at.isoformat()
        }
        
        return jsonify({
            'success': True,
            'message': 'Program retrieved successfully',
            'program': program_data
        })
        
    except Exception as e:
        logger.error(f"Android programs API error: {e}")
        return jsonify({'success': False, 'message': 'Internal server error'}), 500

@app.route('/api/android/generate-config', methods=['POST'])
def api_android_generate_config():
    """Generate card configuration for Android app"""
    try:
        auth_header = request.headers.get('Authorization', '')
        if not auth_header.startswith('Bearer '):
            return jsonify({'success': False, 'message': 'Invalid authorization header'}), 401
        
        token = auth_header.replace('Bearer ', '')
        distribution = ProgramDistribution.query.filter_by(access_token=token).first()
        
        if not distribution or distribution.expires_at < datetime.utcnow():
            return jsonify({'success': False, 'message': 'Invalid or expired token'}), 401
        
        if distribution.is_used:
            return jsonify({'success': False, 'message': 'Token already used'}), 410
        
        # Get the program
        program = CardProgram.query.get(distribution.program_id)
        if not program:
            return jsonify({'success': False, 'message': 'Program not found'}), 404
        
        # Process the configuration request
        config_request = request.get_json()
        sector_data = json.loads(program.sector_data)
        
        # Generate optimized config for Android NFC
        android_config = {
            'card_type': config_request.get('card_type', 'classic_1k'),
            'sector_data': sector_data,
            'programming_instructions': {
                'authenticate_with_default_keys': True,
                'validate_uid': True,
                'verify_after_write': True
            },
            'generated_at': datetime.utcnow().isoformat()
        }
        
        return jsonify({
            'success': True,
            'message': 'Configuration generated successfully',
            'config': android_config
        })
        
    except Exception as e:
        logger.error(f"Android generate config API error: {e}")
        return jsonify({'success': False, 'message': 'Configuration generation failed'}), 500

@app.route('/api/android/programming-result', methods=['POST'])
def api_android_programming_result():
    """Receive programming results from Android app"""
    try:
        auth_header = request.headers.get('Authorization', '')
        if not auth_header.startswith('Bearer '):
            return jsonify({'success': False, 'message': 'Invalid authorization header'}), 401
        
        token = auth_header.replace('Bearer ', '')
        distribution = ProgramDistribution.query.filter_by(access_token=token).first()
        
        if not distribution or distribution.expires_at < datetime.utcnow():
            return jsonify({'success': False, 'message': 'Invalid or expired token'}), 401
        
        result_data = request.get_json()
        
        # Log the programming result
        programming_log = {
            'distribution_id': distribution.id,
            'card_uid': result_data.get('cardUid'),
            'success': result_data.get('success'),
            'error_message': result_data.get('errorMessage'),
            'timestamp': result_data.get('timestamp'),
            'user_id': result_data.get('userId')
        }
        
        # Atomic transaction for single-use enforcement and audit logging
        try:
            # Parse success value (handle both boolean and string)
            success_value = result_data.get('success')
            if isinstance(success_value, str):
                success_bool = success_value.lower() == 'true'
            else:
                success_bool = bool(success_value)
            
            # Create programming log entry for audit trail
            programming_log_entry = ProgrammingLog(
                distribution_id=distribution.id,
                user_id=result_data.get('userId'),
                card_uid=result_data.get('cardUid'),
                success=success_bool,
                error_message=result_data.get('errorMessage'),
                programming_timestamp=datetime.fromtimestamp(int(result_data.get('timestamp', 0)) / 1000) if result_data.get('timestamp') else None,
                ip_address=request.environ.get('HTTP_X_FORWARDED_FOR', request.environ.get('REMOTE_ADDR')),
                user_agent=request.headers.get('User-Agent')
            )
            db.session.add(programming_log_entry)
            
            # Mark distribution as used if successful (enforce single-use atomically)
            if success_bool:
                distribution.is_used = True
                distribution.used_at = datetime.utcnow()
            
            db.session.commit()
        except Exception as e:
            db.session.rollback()
            logger.error(f"Failed to record programming result atomically: {e}")
            return jsonify({'success': False, 'message': 'Failed to record result'}), 500
        logger.info(f"Android programming result logged: success={programming_log_entry.success}, uid={programming_log_entry.card_uid}")
        
        return jsonify({
            'success': True,
            'message': 'Programming result recorded successfully'
        })
        
    except Exception as e:
        logger.error(f"Android programming result API error: {e}")
        return jsonify({'success': False, 'message': 'Failed to record result'}), 500

@app.route('/api/mark-used/<token>', methods=['POST'])
def api_mark_used(token):
    """Mark a program access as used (called from web interface)"""
    try:
        distribution = ProgramDistribution.query.filter_by(access_token=token).first()
        if distribution and not distribution.is_used:
            distribution.is_used = True
            distribution.used_at = datetime.utcnow()
            db.session.commit()
        return jsonify({'success': True})
    except Exception as e:
        logger.error(f"Mark used API error: {e}")
        return jsonify({'success': False}), 500

@app.route('/api/latest-apk', methods=['GET'])
def api_latest_apk():
    """Get latest APK download information from GitHub releases"""
    try:
        # GitHub repository info - update these for your repo
        github_owner = os.environ.get('GITHUB_OWNER', '513solutions')
        github_repo = os.environ.get('GITHUB_REPO', 'mifare-programmer')
        github_api_url = f"https://api.github.com/repos/{github_owner}/{github_repo}/releases/latest"
        
        # Set up headers for GitHub API
        headers = {'Accept': 'application/vnd.github.v3+json'}
        github_token = os.environ.get('GITHUB_TOKEN')
        if github_token:
            headers['Authorization'] = f'token {github_token}'
        
        # Fetch latest release info from GitHub
        response = requests.get(github_api_url, headers=headers, timeout=10)
        
        if response.status_code == 200:
            release_data = response.json()
            
            # Find the APK asset in the release
            apk_asset = None
            for asset in release_data.get('assets', []):
                if asset['name'].endswith('.apk'):
                    apk_asset = asset
                    break
            
            if apk_asset:
                return jsonify({
                    'success': True,
                    'download_url': apk_asset['browser_download_url'],
                    'filename': apk_asset['name'],
                    'version': release_data['tag_name'],
                    'size': f"{apk_asset['size'] / (1024*1024):.1f} MB",
                    'updated_at': release_data['published_at'],
                    'release_notes': release_data.get('body', '')[:500] + '...' if len(release_data.get('body', '')) > 500 else release_data.get('body', '')
                })
            else:
                # No APK found in latest release
                return jsonify({
                    'success': False,
                    'message': 'No APK found in latest release',
                    'fallback_url': f"https://github.com/{github_owner}/{github_repo}/releases/latest"
                }), 404
        else:
            # GitHub API error - provide fallback
            logger.warning(f"GitHub API returned {response.status_code}: {response.text}")
            return jsonify({
                'success': False,
                'message': 'Unable to fetch latest release info',
                'fallback_url': f"https://github.com/{github_owner}/{github_repo}/releases/latest"
            }), 502
            
    except requests.exceptions.Timeout:
        logger.error("GitHub API timeout")
        return jsonify({
            'success': False,
            'message': 'GitHub API timeout',
            'fallback_url': f"https://github.com/{github_owner}/{github_repo}/releases/latest"
        }), 504
    except Exception as e:
        logger.error(f"Latest APK API error: {e}")
        return jsonify({
            'success': False,
            'message': 'APK information unavailable',
            'fallback_url': f"https://github.com/{github_owner}/{github_repo}/releases/latest"
        }), 500

@app.route('/favicon.ico')
def favicon():
    return '', 404

# APK Download Routes
@app.route('/apk')
def apk_download_page():
    """APK download page"""
    try:
        apk_path = os.path.join('static', 'app-distribution-link-fixed.apk')
        if os.path.exists(apk_path):
            apk_size = round(os.path.getsize(apk_path) / (1024*1024), 1)
            apk_exists = True
            # Get MD5 for verification
            import hashlib
            with open(apk_path, 'rb') as f:
                apk_md5 = hashlib.md5(f.read()).hexdigest()
        else:
            apk_size = 0
            apk_exists = False
            apk_md5 = ""
        
        return render_template('apk_download.html', 
                             apk_size=apk_size, 
                             apk_exists=apk_exists,
                             apk_md5=apk_md5)
    except Exception as e:
        logger.error(f"APK page error: {e}")
        return f"Error loading APK page: {e}", 500

@app.route('/apk/download')
def download_apk():
    """Direct APK download"""
    try:
        apk_path = os.path.join('static', 'app-distribution-link-fixed.apk')
        if os.path.exists(apk_path):
            return send_file(
                apk_path,
                as_attachment=True,
                download_name='mifare-app-v1.1.apk',
                mimetype='application/vnd.android.package-archive',
                max_age=0,  # Disable caching
                conditional=False  # Disable range requests to prevent HTTP 206
            )
        else:
            return jsonify({'error': 'APK file not found'}), 404
    except Exception as e:
        logger.error(f"APK download error: {e}")
        return jsonify({'error': str(e)}), 500

# Initialize database and create admin user
def init_db():
    with app.app_context():
        try:
            db.create_all()
            
            # Create admin user if it doesn't exist
            admin = User.query.filter_by(username='admin').first()
            if not admin:
                admin = User(
                    username='admin',
                    email='admin@mifare-system.local',
                    password_hash=generate_password_hash('admin123'),
                    is_admin=True
                )
                db.session.add(admin)
                db.session.commit()
                print("✅ Admin user created: admin/admin123")
            else:
                # Update password if it exists
                admin.password_hash = generate_password_hash('admin123')
                db.session.commit()
                print("✅ Admin password updated: admin/admin123")
        except Exception as e:
            print(f"❌ Database initialization error: {e}")
            # Try direct SQL approach for admin user
            try:
                DATABASE_URL = os.environ.get('DATABASE_URL')
                conn = psycopg2.connect(DATABASE_URL)
                cursor = conn.cursor()
                
                # Check if admin exists
                cursor.execute('SELECT COUNT(*) FROM "user" WHERE username = %s', ('admin',))
                admin_exists = cursor.fetchone()[0] > 0
                
                if not admin_exists:
                    password_hash = generate_password_hash('admin123')
                    cursor.execute(
                        'INSERT INTO "user" (username, email, password_hash, is_admin, created_at) VALUES (%s, %s, %s, %s, %s)',
                        ('admin', 'admin@mifare-system.local', password_hash, True, datetime.utcnow())
                    )
                    conn.commit()
                    print("✅ Admin user created via direct SQL: admin/admin123")
                
                conn.close()
            except Exception as sql_e:
                print(f"❌ Direct SQL admin creation also failed: {sql_e}")

if __name__ == '__main__':
    init_db()
    app.run(host='0.0.0.0', port=5000, debug=True)