import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import ts from 'typescript';

async function load(path, replacements = {}) {
  let source = readFileSync(new URL(path, import.meta.url), 'utf8');
  for (const [from, to] of Object.entries(replacements)) source = source.replace(from, to);
  const js = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 } }).outputText;
  return import('data:text/javascript;base64,' + Buffer.from(js + '\n//' + Math.random()).toString('base64'));
}
const startupModule = () => load('../lib/startup.ts', { "import { auth, type User } from './devpilot-api';": 'const auth = { restore: async () => null }; type User = {id: string};' });
const flush = () => new Promise(resolve => setImmediate(resolve));
const deferred = () => { let resolve, reject; const promise = new Promise((a,b) => { resolve=a; reject=b; }); return {promise,resolve,reject}; };

for (const [name, user] of [['fresh visitor', null], ['valid session', {id:'saved'}], ['expired session', null]]) {
  test(name + ' restores once after readiness, and shares checks across subscribers', async () => {
    const {createStartup} = await startupModule();
    const ready = deferred(); let checks=0, restores=0;
    const store=createStartup({wait:()=>{checks++;return ready.promise},restore:async()=>{restores++;return user}});
    const off=store.subscribe(()=>{}), off2=store.subscribe(()=>{});
    assert.equal(store.getSnapshot().phase,'connecting'); assert.equal(restores,0);
    ready.resolve(); await flush();
    assert.equal(store.getSnapshot().phase,'ready');assert.deepEqual(store.getSnapshot().user,user);
    assert.equal(checks,1); assert.equal(restores,1);
    off();off2();await flush();const off3=store.subscribe(()=>{});assert.equal(checks,1);off3();
  });
}

test('delayed readiness increases delays, then recovers automatically',async()=>{
  const {waitForBackend}=await startupModule();let time=0,calls=0;const delays=[];
  await waitForBackend(new AbortController().signal,{now:()=>time,check:async()=>++calls===5,sleep:async ms=>{delays.push(ms);time+=ms}});
  assert.deepEqual(delays,[1000,2000,4000,8000]);assert.equal(calls,5);
});
test('outage respects two-minute budget and caps increasing delays',async()=>{
  const {waitForBackend}=await startupModule();let time=0;const delays=[];
  await assert.rejects(waitForBackend(new AbortController().signal,{now:()=>time,check:async()=>false,sleep:async ms=>{delays.push(ms);time+=ms}}),/deadline/);
  assert.equal(time,120000);assert.ok(delays.every(ms=>ms<=10000));
});
test('readiness GET uses no-store and a short abort deadline',async()=>{
  const {waitForBackend}=await startupModule();const original=AbortSignal.timeout;const timeouts=[];
  AbortSignal.timeout=ms=>{timeouts.push(ms);return new AbortController().signal};
  try{global.fetch=async(url,options)=>{assert.equal(url,'/api/readiness');assert.equal(options.cache,'no-store');return Response.json({ready:true})};await waitForBackend(new AbortController().signal);assert.deepEqual(timeouts,[5000]);}
  finally{AbortSignal.timeout=original;}
});
test('unmount cancels in-flight readiness; stale completion cannot restore',async()=>{
  const {createStartup}=await startupModule();const ready=deferred();let signal,restores=0;
  const store=createStartup({wait:s=>{signal=s;return ready.promise},restore:async()=>{restores++;return null}});
  const off=store.subscribe(()=>{});off();await flush();assert.equal(signal.aborted,true);
  ready.resolve();await flush();assert.equal(restores,0);
});
test('cancellation during retry delay stops subsequent checks',async()=>{
  const {waitForBackend}=await startupModule();const controller=new AbortController();let calls=0;
  const work=waitForBackend(controller.signal,{check:async()=>{calls++;return false}});
  await flush();controller.abort();await assert.rejects(work,{name:'AbortError'});assert.equal(calls,1);
});
test('Strict Mode resubscription shares check; retry after outage recovers',async()=>{
  const {createStartup}=await startupModule();let checks=0;
  const store=createStartup({wait:async()=>{if(++checks===1)throw Error('offline')},restore:async()=>null});
  const off=store.subscribe(()=>{});off();const off2=store.subscribe(()=>{});await flush();
  assert.equal(checks,1);assert.equal(store.getSnapshot().phase,'unavailable');
  store.retry();store.retry();await flush();assert.equal(checks,2);assert.equal(store.getSnapshot().phase,'ready');off2();
});
test('failed rotating restoration is not retried on remount and stays separate from readiness',async()=>{
  const {createStartup}=await startupModule();let restores=0;
  const store=createStartup({wait:async()=>{},restore:async()=>{restores++;throw Error('network')}});
  const off=store.subscribe(()=>{});await flush();assert.equal(store.getSnapshot().phase,'ready');assert.match(store.getSnapshot().sessionMessage,/couldn’t restore/);
  store.retry();off();await flush();const off2=store.subscribe(()=>{});await flush();assert.equal(restores,1);off2();
});
test('new authentication supersedes a stale startup restoration',async()=>{
  const {createStartup}=await startupModule();const restore=deferred();
  const store=createStartup({wait:async()=>{},restore:()=>restore.promise});const off=store.subscribe(()=>{});await flush();
  store.setUser({id:'new'});restore.resolve({id:'old'});await flush();assert.equal(store.getSnapshot().user.id,'new');off();
});
test('navigation during rotating refresh reuses its result without another POST',async()=>{
  const {createStartup}=await startupModule();const restore=deferred();let calls=0;
  const store=createStartup({wait:async()=>{},restore:()=>{calls++;return restore.promise}});
  const off=store.subscribe(()=>{});await flush();off();await flush();const off2=store.subscribe(()=>{});await flush();restore.resolve({id:'saved'});await flush();
  assert.equal(calls,1);assert.equal(store.getSnapshot().user.id,'saved');off2();
});
test('missing or empty refresh cookie returns uncached 401 without upstream or configuration',async()=>{
  const {POST}=await load('../app/api/v1/[...path]/route.ts');let calls=0;global.fetch=async()=>{calls++;throw Error('must not fetch')};
  const previous=process.env.BACKEND_URL;delete process.env.BACKEND_URL;
  try{for(const value of [undefined,{value:''}]){const response=await POST({url:'http://localhost/api/v1/auth/refresh',method:'POST',cookies:{get:()=>value}});assert.equal(response.status,401);assert.equal(response.headers.get('cache-control'),'no-store');}assert.equal(calls,0);}
  finally{if(previous===undefined)delete process.env.BACKEND_URL;else process.env.BACKEND_URL=previous;}
});
test('health proxy checks actuator status, strips details, and never caches',async()=>{
  const {GET}=await load('../app/api/readiness/route.ts');const previous=process.env.BACKEND_URL;process.env.BACKEND_URL='http://backend/';
  try{for(const [upstream,expected] of [[Response.json({status:'UP',secret:'details'}),200],[Response.json({status:'DOWN'}),503],[new Response('<html>Starting</html>'),503]]){global.fetch=async(url,options)=>{assert.equal(url,'http://backend/actuator/health');assert.equal(options.cache,'no-store');assert.ok(options.signal);return upstream};const response=await GET(new Request('http://localhost/api/readiness'));assert.equal(response.status,expected);assert.equal(response.headers.get('cache-control'),'no-store');assert.deepEqual(await response.json(),{ready:expected===200});}
  global.fetch=async()=>{throw Error('timeout')};assert.equal((await GET(new Request('http://localhost/api/readiness'))).status,503);
  }finally{if(previous===undefined)delete process.env.BACKEND_URL;else process.env.BACKEND_URL=previous;}
});

// Render the actual application with lightweight boundary components; no backend is needed.
async function renderPublic(pathname, state) {
  const React = await import('react');
  const {renderToStaticMarkup} = await import('react-dom/server');
  const {createRequire} = await import('node:module');
  const require = createRequire(import.meta.url);
  const {CONNECTING_MESSAGE} = await startupModule();
  const source=readFileSync(new URL('../components/devpilot/app.tsx',import.meta.url),'utf8');
  const js=ts.transpileModule(source,{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022,jsx:ts.JsxEmit.ReactJSX,esModuleInterop:true}}).outputText;
  const empty=()=>null;
  const boundaries={
    'next/navigation':{usePathname:()=>pathname,useRouter:()=>({push(){},replace(){}})},
    'next/link':{default:props=>React.createElement('a',props),__esModule:true},
    '@/lib/startup':{CONNECTING_MESSAGE,startup:{subscribe:()=>()=>{},getSnapshot:()=>state,getServerSnapshot:()=>state}},
    '@/lib/devpilot-api':{auth:{}},
    './use-resource':{useResource:()=>({})},
    '@/components/ui/button':{Button:({size,variant,...props})=>{void size;void variant;return React.createElement('button',props)}},
    '@/components/ui/input':{Input:props=>React.createElement('input',props)},
  };
  const compiled={exports:{}};
  new Function('require','module','exports',js)(name=>boundaries[name]??(name.startsWith('react')?require(name):new Proxy({},{get:()=>empty})),compiled,compiled.exports);
  return renderToStaticMarkup(React.createElement(compiled.exports.default));
}
for(const pathname of ['/','/login','/register']){
 test(pathname+' renders editable credentials immediately while submission is disabled',async()=>{
  const html=await renderPublic(pathname,{phase:'connecting',user:null,sessionMessage:''});
  assert.match(html,/<form/);assert.match(html,/Getting DevPilot ready/);assert.match(html,/first visit after inactivity/);
  const inputs=html.match(/<input[^>]*>/g);assert.ok(inputs.length>=2);assert.ok(inputs.every(input=>!input.includes('disabled')));
  assert.match(html,/<button[^>]*disabled=""[^>]*>(?:Sign in|Create workspace)/);assert.doesNotMatch(html,/role="alert"/);
  if(pathname==='/register')assert.match(html,/name="name"/);
 });
}
test('outage renders friendly retry action outside the form and keeps submission disabled',async()=>{
 const html=await renderPublic('/login',{phase:'unavailable',user:null,sessionMessage:''});
 assert.match(html,/taking longer than expected/);assert.match(html,/>Try again<\/button>/);assert.ok(html.indexOf('Try again')<html.indexOf('<form'));
 assert.match(html,/<button[^>]*disabled=""[^>]*>Sign in/);assert.doesNotMatch(html,/role="alert"/);
});
test('ready form enables submission and session warnings are not credential errors',async()=>{
 const html=await renderPublic('/login',{phase:'ready',user:null,sessionMessage:'Session restoration could not finish.'});
 assert.match(html,/role="status"/);assert.match(html,/Session restoration could not finish/);assert.doesNotMatch(html,/disabled=""/);assert.doesNotMatch(html,/role="alert"/);
});
