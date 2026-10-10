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

test('repository chat sends a fresh indexed SHA and preserves response sources',async()=>{
 const c=await client();const calls=[];let commit='a'.repeat(40);
 const answer=[{id:'answer',role:'assistant',content:'Answer',sources:[{sourcePath:'A.java',chunkIndex:0,commitSha:commit}]}];
 global.fetch=async(url,opts)=>{calls.push([url,opts]);return opts.method==='GET'?response(200,{id:'r',latestIndexedCommitSha:commit}):response(200,answer)};
 assert.deepEqual(await c.sendChatMessage('r','conversation','question'),answer);
 assert.equal(calls[0][0],'/api/v1/repositories/r');
 assert.equal(calls[1][0],'/api/v1/repositories/r/conversations/conversation/messages');
 assert.deepEqual(JSON.parse(calls[1][1].body),{content:'question',commitSha:commit});
 commit='b'.repeat(40);
 await c.sendChatMessage('r','conversation','next');
 assert.deepEqual(JSON.parse(calls[3][1].body),{content:'next',commitSha:commit});
});
test('unindexed and older repository responses preserve background chat',async()=>{
 for(const repository of [{id:'r',latestIndexedCommitSha:null},{id:'r'},{id:'r',latestIndexedCommitSha:'main'}]){
  const c=await client();let body;const legacy=[{id:'answer',role:'assistant',content:'Background'}];
  global.fetch=async(url,opts)=>{if(opts.method==='GET')return response(200,repository);body=JSON.parse(opts.body);return response(200,legacy)};
  assert.deepEqual(await c.sendChatMessage('r','c','question'),legacy);
  assert.deepEqual(body,{content:'question'});
 }
});
test('switching repositories never reuses an indexed commit',async()=>{
 const c=await client();const bodies=[];const commit='a'.repeat(40);
 global.fetch=async(url,opts)=>{
  if(opts.method==='GET')return response(200,url.endsWith('/r1')?{id:'r1',latestIndexedCommitSha:commit}:{id:'r2',latestIndexedCommitSha:null});
  bodies.push([url,JSON.parse(opts.body)]);return response(200,[]);
 };
 await c.sendChatMessage('r1','c1','first');await c.sendChatMessage('r2','c2','second');
 assert.deepEqual(bodies,[['/api/v1/repositories/r1/conversations/c1/messages',{content:'first',commitSha:commit}],['/api/v1/repositories/r2/conversations/c2/messages',{content:'second'}]]);
});
test('mismatched repository metadata cannot send a cross-repository SHA',async()=>{
 const c=await client();let posts=0;
 global.fetch=async(url,opts)=>{if(opts.method==='POST')posts++;return response(200,{id:'other',latestIndexedCommitSha:'a'.repeat(40)})};
 await assert.rejects(c.sendChatMessage('r','c','question'),/Repository changed/);assert.equal(posts,0);
});
test('failed repository metadata lookup does not silently send background chat',async()=>{
 const c=await client();let posts=0;
 global.fetch=async(url,opts)=>{if(opts.method==='POST')posts++;return response(503,{message:'Temporarily unavailable'})};
 await assert.rejects(c.sendChatMessage('r','c','question'),/Temporarily unavailable/);assert.equal(posts,0);
});

const deferred = () => { let resolve, reject; const promise = new Promise((yes,no) => { resolve=yes; reject=no; }); return {promise,resolve,reject}; };
const flush = () => new Promise(resolve => setImmediate(resolve));
async function signedIn() {
 const c=await client();
 global.fetch=async()=>response(200,{accessToken:'old-access',user:{id:'u'}});
 await c.auth.enter('login',{});
 return c;
}
async function withExpirationEvents(run) {
 const previous=global.window;
 const events=[];const target=new EventTarget();
 target.addEventListener('devpilot:expired',()=>events.push('expired'));
 global.window=target;
 try { await run(events); }
 finally { if(previous===undefined)delete global.window;else global.window=previous; }
}

test('expired bearer is omitted from refresh; retry uses the new token and does not expire the UI session',async()=>{
 await withExpirationEvents(async events=>{
  const c=await signedIn();const calls=[];
  global.fetch=async(url,opts)=>{
   calls.push([url,opts]);
   if(url.endsWith('/refresh')){
    assert.equal(opts.headers.Authorization,undefined);assert.equal(opts.credentials,'same-origin');
    return response(200,{accessToken:'new-access',user:{id:'u'}});
   }
   return opts.headers.Authorization==='Bearer old-access'?response(401,{message:'expired'}):response(200,{ok:true});
  };
  assert.deepEqual(await c.api('/users/me'),{ok:true});
  assert.equal(calls.length,3);
  assert.equal(calls[2][1].headers.Authorization,'Bearer new-access');
  assert.deepEqual(events,[]);
  await c.api('/repositories');
  assert.equal(calls.at(-1)[1].headers.Authorization,'Bearer new-access');
  assert.equal(calls.filter(([url])=>url.endsWith('/refresh')).length,1);
 });
});

test('simultaneous expired requests share refresh, and late old-token 401s reuse the renewed bearer',async()=>{
 await withExpirationEvents(async events=>{
  const c=await signedIn();const refresh=deferred(),late=deferred();let rotations=0;
  const requests=[];
  global.fetch=async(url,opts)=>{
   if(url.endsWith('/refresh')){rotations++;return refresh.promise;}
   requests.push([url,opts.headers.Authorization]);
   if(opts.headers.Authorization==='Bearer new-access')return response(200,{ok:true});
   if(url.endsWith('/late'))return late.promise;
   return response(401,{message:'expired'});
  };
  const first=c.api('/first'),second=c.api('/second'),delayed=c.api('/late');
  await flush();assert.equal(rotations,1);
  refresh.resolve(response(200,{accessToken:'new-access',user:{id:'u'}}));
  await Promise.all([first,second]);
  late.resolve(response(401,{message:'expired'}));await delayed;
  assert.equal(rotations,1);assert.deepEqual(events,[]);
  for(const path of ['/first','/second','/late']){
   assert.deepEqual(requests.filter(([url])=>url.endsWith(path)).map(([,token])=>token),['Bearer old-access','Bearer new-access']);
  }
 });
});

test('invalid refresh expires the session once without competing rotations or retries',async()=>{
 await withExpirationEvents(async events=>{
  const c=await signedIn();const refresh=deferred(),late=deferred();let rotations=0;
  global.fetch=async(url)=>{
   if(url.endsWith('/refresh')){rotations++;return refresh.promise;}
   if(url.endsWith('/late'))return late.promise;
   return response(401,{message:'unauthorized'});
  };
  const first=assert.rejects(c.api('/first'),e=>e.status===401);
  const second=assert.rejects(c.api('/second'),e=>e.status===401);
  const delayed=assert.rejects(c.api('/late'),e=>e.status===401);
  await flush();assert.equal(rotations,1);
  refresh.resolve(response(401,{message:'Session expired'}));await Promise.all([first,second]);
  late.resolve(response(401,{message:'unauthorized'}));await delayed;
  assert.equal(rotations,1);assert.deepEqual(events,['expired']);
  await assert.rejects(c.api('/another'),e=>e.status===401);
  assert.equal(rotations,1);assert.deepEqual(events,['expired']);
 });
});

test('a retried request receiving 401 is not refreshed recursively',async()=>{
 await withExpirationEvents(async events=>{
  const c=await signedIn();let requests=0,rotations=0;
  global.fetch=async(url)=>{
   if(url.endsWith('/refresh')){rotations++;return response(200,{accessToken:'new-access',user:{id:'u'}});}
   requests++;return response(401,{message:'still unauthorized'});
  };
  await assert.rejects(c.api('/repositories'),e=>e.status===401);
  assert.equal(rotations,1);assert.equal(requests,2);assert.deepEqual(events,[]);
 });
});

test('auth endpoint failures never attach a bearer, refresh recursively, or expire the workspace',async()=>{
 await withExpirationEvents(async events=>{
  const c=await signedIn();const calls=[];
  global.fetch=async(url,opts)=>{calls.push(url);assert.equal(opts.headers.Authorization,undefined);return response(401,{message:'invalid credentials'});};
  for(const path of ['/auth/login','/auth/register','/auth/refresh','/auth/logout']){
   await assert.rejects(c.api(path,'POST',{}),e=>e.status===401);
  }
  assert.equal(calls.length,4);assert.deepEqual(events,[]);
 });
});

test('transient refresh failure retains auth and a later request can recover',async()=>{
 await withExpirationEvents(async events=>{
  const c=await signedIn();let rotations=0;
  global.fetch=async(url,opts)=>{
   if(url.endsWith('/refresh'))return ++rotations===1?response(502,{message:'temporarily unavailable'}):response(200,{accessToken:'new-access',user:{id:'u'}});
   return opts.headers.Authorization==='Bearer new-access'?response(200,{ok:true}):response(401,{message:'expired'});
  };
  await assert.rejects(c.api('/repositories'),e=>e.status===502);
  assert.deepEqual(events,[]);
  assert.deepEqual(await c.api('/repositories'),{ok:true});
  assert.equal(rotations,2);assert.deepEqual(events,[]);
 });
});

test('logout waits for rotation, shares one revocation, and blocks delayed 401s from restoring auth',async()=>{
 await withExpirationEvents(async events=>{
  const c=await signedIn();const refresh=deferred(),revocation=deferred(),late=deferred();
  let rotations=0,logouts=0;
  global.fetch=async(url,opts)=>{
   if(url.endsWith('/refresh')){rotations++;assert.equal(opts.headers.Authorization,undefined);return refresh.promise;}
   if(url.endsWith('/logout')){logouts++;assert.equal(opts.headers.Authorization,undefined);return revocation.promise;}
   if(url.endsWith('/late'))return late.promise;
   return response(401,{message:'expired'});
  };
  const inFlight=assert.rejects(c.api('/first'),e=>e.status===401);
  const delayed=assert.rejects(c.api('/late'),e=>e.status===401);
  await flush();
  const logout=c.auth.logout(),duplicate=c.auth.logout();
  assert.strictEqual(logout,duplicate);assert.equal(logouts,0);
  const restoration=c.auth.restore();
  await assert.rejects(c.auth.enter('login',{}),/sign-out request/);
  refresh.resolve(response(200,{accessToken:'new-access',user:{id:'u'}}));
  await flush();assert.equal(logouts,1);
  revocation.resolve(response(204));
  await Promise.all([logout,duplicate]);assert.equal(await restoration,null);
  await inFlight;
  late.resolve(response(401,{message:'expired'}));await delayed;
  assert.equal(await c.auth.restore(),null);
  assert.equal(rotations,1);assert.equal(logouts,1);assert.deepEqual(events,[]);
  let bearer;
  global.fetch=async(url,opts)=>{bearer=opts.headers.Authorization;return response(200,{ok:true});};
  await c.api('/ordinary');assert.equal(bearer,undefined);
 });
});

test('failed logout preserves authentication and a subsequent logout can revoke it',async()=>{
 const c=await signedIn();let bearer;
 global.fetch=async(url,opts)=>{
  bearer=opts.headers.Authorization;
  return url.endsWith('/logout')?response(502,{message:'revocation unavailable'}):response(200,{ok:true});
 };
 await assert.rejects(c.auth.logout(),e=>e.status===502);
 await c.api('/repositories');assert.equal(bearer,'Bearer old-access');
 global.fetch=async()=>response(204);await c.auth.logout();
 assert.equal(await c.auth.restore(),null);
});

test('late unauthorized requests from a previous login cannot refresh or clear the new session',async()=>{
 await withExpirationEvents(async events=>{
  const c=await signedIn();const late=deferred();let rotations=0;
  global.fetch=async(url)=>{
   if(url.endsWith('/login'))return response(200,{accessToken:'new-user-access',user:{id:'new-user'}});
   if(url.endsWith('/refresh')){rotations++;return response(401,{message:'invalid'});}
   return late.promise;
  };
  const old=assert.rejects(c.api('/old-request'),e=>e.status===401);
  await c.auth.enter('login',{});
  late.resolve(response(401,{message:'old session'}));await old;
  assert.equal(rotations,0);assert.deepEqual(events,[]);
  let bearer;global.fetch=async(url,opts)=>{bearer=opts.headers.Authorization;return response(200,{});};
  await c.api('/current');assert.equal(bearer,'Bearer new-user-access');
 });
});

test('successful sign-in starts a new session after definitive refresh rejection',async()=>{
 const c=await signedIn();
 global.fetch=async()=>response(401,{message:'expired'});
 assert.equal(await c.auth.restore(),null);
 global.fetch=async()=>response(200,{accessToken:'new-login',user:{id:'u'}});
 await c.auth.enter('login',{});
 let bearer;global.fetch=async(url,opts)=>{bearer=opts.headers.Authorization;return response(200,{});};
 await c.api('/repositories');assert.equal(bearer,'Bearer new-login');
});
