#!/usr/bin/env python3
"""Generate local development secrets once, without printing them."""
from pathlib import Path
import secrets, base64, os
root=Path(__file__).resolve().parent.parent
p=root/'.env'
if p.exists():
    raise SystemExit('.env already exists; preserve it or move it explicitly before regenerating.')
values={'DATABASE_PASSWORD':secrets.token_urlsafe(32),'JWT_SECRET':secrets.token_urlsafe(48),'ENCRYPTION_KEY':base64.b64encode(secrets.token_bytes(32)).decode(),'WEBHOOK_SECRET':secrets.token_urlsafe(40),'FRONTEND_ORIGIN':'http://localhost:5173','SECURE_COOKIES':'false','AI_MODE':'local','AI_API_KEY':'','AI_MODEL':'gpt-4.1-mini','AI_BASE_URL':'https://api.openai.com/v1','BACKEND_URL':'http://localhost:8080'}
fd=os.open(p,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
with os.fdopen(fd,'w') as f:
    f.write(''.join(k+'='+v+'\n' for k,v in values.items()))
print('Created .env with unique local development secrets.')
