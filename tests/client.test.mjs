import test from 'node:test';import assert from 'node:assert/strict';import {readFileSync} from 'node:fs';import ts from 'typescript';
async function client(){const source=readFileSync(new URL('../lib/devpilot-api.ts',import.meta.url),'utf8');const js=ts.transpileModule(source,{compilerOptions:{module:ts.ModuleKind.ESNext,target:ts.ScriptTarget.ES2022}}).outputText;return import('data:text/javascript;base64,'+Buffer.from(js+'\n//'+Math.random()).toString('base64'));}
const response=(status,data)=>new Response(data===undefined?null:JSON.stringify(data),{status,headers:{'content-type':'application/json'}});
test('access tokens are sent to same-origin API, and never persisted',async()=>{const c=await client();const calls=[];global.fetch=async(url,opts)=>{calls.push([url,opts]);return url.endsWith('/login')?response(200,{accessToken:'short-lived',user:{id:'u'}}):response(200,{id:'repo'})};await c.auth.enter('login',{email:'a@example.com',password:'test'});await c.api('/repositories/r');assert.equal(calls[1][0],'/api/v1/repositories/r');assert.equal(calls[1][1].headers.Authorization,'Bearer short-lived');assert.equal(calls[1][1].credentials,'same-origin');});
test('expired access token refreshes and retries once',async()=>{const c=await client();let apiCalls=0;let refreshes=0;global.fetch=async(url)=>{if(url.endsWith('/refresh')){refreshes++;return response(200,{accessToken:'new',user:{id:'u'}})}return ++apiCalls===1?response(401,{message:'expired'}):response(200,{ok:true})};assert.deepEqual(await c.api('/repositories'),{ok:true});assert.equal(refreshes,1);assert.equal(apiCalls,2);});
test('simultaneous refresh requests share one rotation',async()=>{const c=await client();let calls=0;global.fetch=async()=>{calls++;return response(200,{accessToken:'new',user:{id:'u'}})};await Promise.all([c.auth.restore(),c.auth.restore()]);assert.equal(calls,1);});
test('validation errors retain field messages',async()=>{const c=await client();global.fetch=async()=>response(400,{message:'Validation failed',fieldErrors:['password: too short']});await assert.rejects(c.api('/repositories'),/password: too short/);});
test('network failure is distinguished from signed-out state',async()=>{const c=await client();global.fetch=async()=>{throw new Error('network')};await assert.rejects(c.auth.restore(),/Cannot reach DevPilot/);global.fetch=async()=>response(401,{message:'Unauthorized'});assert.equal(await c.auth.restore(),null);});
test('logout clears access token only after revocation succeeds',async()=>{const c=await client();global.fetch=async()=>response(200,{accessToken:'token',user:{id:'u'}});await c.auth.enter('login',{});global.fetch=async()=>response(204);await c.auth.logout();let authorization;global.fetch=async(url,opts)=>{authorization=opts.headers.Authorization;return response(200,{})};await c.api('/repositories');assert.equal(authorization,undefined);});

test('restore network failures preserve the current access token',async()=>{
 const c=await client();global.fetch=async()=>response(200,{accessToken:'saved',user:{id:'u'}});await c.auth.enter('login',{});
 let refreshes=0;global.fetch=async()=>{refreshes++;throw Error('offline')};await assert.rejects(c.auth.restore());assert.equal(refreshes,1);
 let token;global.fetch=async(url,opts)=>{token=opts.headers.Authorization;return response(200,{})};await c.api('/repositories');assert.equal(token,'Bearer saved');
});
test('login waits for rotating refresh and newer credentials remain authoritative',async()=>{
 const c=await client();let finish;const calls=[];
 global.fetch=async(url)=>{calls.push(url);if(url.endsWith('/refresh'))return new Promise(resolve=>{finish=resolve});return response(200,{accessToken:'new',user:{id:'new'}})};
 const restoring=c.auth.restore();const login=c.auth.enter('login',{});assert.deepEqual(calls,['/api/v1/auth/refresh']);
 finish(response(200,{accessToken:'old',user:{id:'old'}}));await restoring;await login;
 assert.deepEqual(calls,['/api/v1/auth/refresh','/api/v1/auth/login']);let token;global.fetch=async(url,opts)=>{token=opts.headers.Authorization;return response(200,{})};await c.api('/repositories');assert.equal(token,'Bearer new');
});
test('login and registration failures are never automatically retried',async()=>{
 for(const mode of ['login','register']){const c=await client();let calls=0;global.fetch=async()=>{calls++;throw Error('offline')};await assert.rejects(c.auth.enter(mode,{}));assert.equal(calls,1);}
});
test('duplicate sign-ins are rejected and restoration shares the active sign-in',async()=>{
 const c=await client();let finish,calls=0;global.fetch=async()=>{calls++;return new Promise(resolve=>{finish=resolve})};
 const login=c.auth.enter('login',{});await assert.rejects(c.auth.enter('login',{}),/already in progress/);const restore=c.auth.restore();
 finish(response(200,{accessToken:'new',user:{id:'new'}}));await login;assert.deepEqual(await restore,{id:'new'});assert.equal(calls,1);
});
