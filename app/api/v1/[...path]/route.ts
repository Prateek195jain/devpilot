import type { NextRequest } from 'next/server';
const ALLOWED=['GET','POST','PATCH','DELETE','OPTIONS'];
async function proxy(request:NextRequest){
 const upstream=process.env.BACKEND_URL;
 if(!upstream)return Response.json({message:'Backend is not configured. Run the supplied Docker Compose stack, or set BACKEND_URL for this frontend.'},{status:503});
 if(!ALLOWED.includes(request.method))return new Response(null,{status:405});
 const url=new URL(request.url);const headers=new Headers();
 for(const name of ['content-type','authorization','cookie','x-hub-signature-256','x-github-delivery','x-github-event']){const value=request.headers.get(name);if(value)headers.set(name,value);}
 const origin=request.headers.get('origin');if(origin)headers.set('origin',origin);
 const body=request.method==='GET'?undefined:await request.arrayBuffer();
 if(body&&body.byteLength>1048576)return Response.json({message:'Request exceeds 1 MiB'},{status:413});
 try{const response=await fetch(upstream.replace(/\/$/,'')+url.pathname+url.search,{method:request.method,headers,body,redirect:'manual',signal:AbortSignal.timeout(85000)});const out=new Headers({'Content-Type':response.headers.get('content-type')||'application/json','Cache-Control':'no-store'});for(const cookie of response.headers.getSetCookie())out.append('set-cookie',cookie);const id=response.headers.get('x-request-id');if(id)out.set('x-request-id',id);return new Response(response.body,{status:response.status,headers:out});}catch{return Response.json({message:'Backend is unavailable. Check the Java service and database.'},{status:502});}
}
export const GET=proxy;export const POST=proxy;export const PATCH=proxy;export const DELETE=proxy;export const OPTIONS=proxy;
