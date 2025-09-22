from flask import Flask, render_template, request, redirect, url_for, flash, session
from flask_sqlalchemy import SQLAlchemy
from flask_login import LoginManager, UserMixin, login_user, logout_user, login_required, current_user
from flask_wtf import FlaskForm
from flask_cors import CORS
from wtforms import StringField, PasswordField, TextAreaField, SelectField
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
    token = db.Column(db.String(255), unique=True, nullable=False)
    expires_at = db.Column(db.DateTime, nullable=False)
    used = db.Column(db.Boolean, default=False)
    created_at = db.Column(db.DateTime, default=datetime.utcnow)

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
        return redirect(url_for('index'))
    
    form = LoginForm()
    logger.debug(f"Login route accessed - Method: {request.method}")
    
    if request.method == 'POST':
        logger.debug(f"Processing POST to /login: {dict(request.form)}")
        
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
                logger.debug(f"Login successful for user: {username}")
                login_user(user)
                next_page = request.args.get('next')
                return redirect(next_page) if next_page else redirect(url_for('index'))
            else:
                logger.debug(f"Login failed for user: {username}")
                flash('Invalid username or password', 'danger')
        else:
            logger.debug(f"Form validation failed: {form.errors}")
            flash('Please check your input', 'danger')
    
    return render_template('login.html', form=form)

@app.route('/register', methods=['GET', 'POST'])
def register():
    if current_user.is_authenticated:
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
                         distributions=distributions)

@app.route('/dashboard')
@login_required
def user_dashboard():
    if current_user.is_admin:
        return redirect(url_for('admin_dashboard'))
    
    distributions = ProgramDistribution.query.filter_by(user_id=current_user.id).all()
    return render_template('user_dashboard.html', distributions=distributions)

@app.route('/create_program', methods=['GET', 'POST'])
@login_required
def create_program():
    if not current_user.is_admin:
        flash('Access denied. Administrator privileges required.', 'danger')
        return redirect(url_for('index'))
    
    form = CardProgramForm()
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
        token = secrets.token_urlsafe(32)
        expires_at = datetime.utcnow() + timedelta(hours=24)
        
        distribution = ProgramDistribution(
            program_id=form.program_id.data,
            user_id=form.user_id.data,
            token=token,
            expires_at=expires_at
        )
        db.session.add(distribution)
        db.session.commit()
        flash('Program distributed successfully!', 'success')
        return redirect(url_for('admin_dashboard'))
    
    return render_template('distribute.html', form=form)

@app.route('/program/<token>')
def program_access(token):
    distribution = ProgramDistribution.query.filter_by(token=token).first_or_404()
    
    if distribution.expires_at < datetime.utcnow():
        flash('This link has expired', 'danger')
        return redirect(url_for('index'))
    
    program = CardProgram.query.get(distribution.program_id)
    return render_template('program_access.html', program=program, distribution=distribution)

@app.route('/favicon.ico')
def favicon():
    return '', 404

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