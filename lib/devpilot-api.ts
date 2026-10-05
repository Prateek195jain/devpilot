export type User={id:string;name:string;email:string;role:string};
export type Page<T>={content:T[];totalElements:number;totalPages:number;number:number};
export type Repo={id:string;fullName:string;description:string;language:string;defaultBranch:string;syncedAt:string};
export type Analysis={id:string;kind:string;ref:string;status:string;source:string;summary:string;error:string;createdAt:string;attempts:number};
export type Finding={id:string;analysisId:string;severity:string;type:string;title:string;description:string;file:string;line:number|null;recommendation:string;confidence:number;source:string};
export type Debt={id:string;title:string;description:string;category:string;severity:string;status:string;estimatedEffort:string;createdAt:string};
export type Memory={id:string;description:string;type:string;occurrences:number;status:string;createdAt:string;lastSeen:string};
export type Doc={id:string;kind:string;documentVersion:number;content:string;source:string;createdAt:string};
export type Conversation={id:string;title:string};
export type Message={id:string;role:string;content:string;createdAt:string};
export type Change={ref:string;title:string;author:string;url:string;state:string};
export type Stats={total:number;open:number;inProgress:number;resolved:number;criticalFindings:number;bySeverity:Record<string,number>;byCategory:Record<string,number>};
let accessToken='';let sessionRevision=0;let refreshing:Promise<User|null>|null=null;let entering:Promise<User>|null=null;
export class ApiError extends Error{constructor(public status:number,message:string){super(message)}}
async function raw<T>(path:string,method='GET',body?:unknown):Promise<T>{
 let response:Response;
 try{response=await fetch('/api/v1'+path,{method,credentials:'same-origin',headers:{'Content-Type':'application/json',...(accessToken?{Authorization:'Bearer '+accessToken}:{})},...(body===undefined?{}:{body:JSON.stringify(body)}),signal:AbortSignal.timeout(90000)});}catch{throw new ApiError(0,'Cannot reach DevPilot. Check that the backend is running.');}
 if(!response.ok){const error=await response.json().catch(()=>({})) as {message?:string;fieldErrors?:string[]};throw new ApiError(response.status,[error.message||'Request failed',...(error.fieldErrors||[])].join(' · '));}
 if(response.status===204)return undefined as T;
 return response.json();
}
export const auth={
 async restore(){
  if(entering)return entering;
  if(!refreshing){
   const revision=sessionRevision;
   refreshing=raw<{accessToken:string;user:User}>('/auth/refresh','POST')
    .then(s=>{if(revision===sessionRevision)accessToken=s.accessToken;return s.user})
    .catch(e=>{if(e instanceof ApiError&&e.status===401){if(revision===sessionRevision)accessToken='';return null;}throw e;})
    .finally(()=>{refreshing=null});
  }
  return refreshing;
 },
 enter(mode:string,body:unknown){
  if(entering)return Promise.reject(new ApiError(409,'A sign-in request is already in progress.'));
  entering=(async()=>{
  // Serialize cookie-changing requests so late refreshes cannot replace a new login cookie.
  if(refreshing)await refreshing.catch(()=>null);
  const revision=++sessionRevision;
  const s=await raw<{accessToken:string;user:User}>('/auth/'+mode,'POST',body);
  if(revision===sessionRevision)accessToken=s.accessToken;
  return s.user;
  })().finally(()=>{entering=null});
  return entering;
 },
 async logout(){if(entering)await entering.catch(()=>null);if(refreshing)await refreshing.catch(()=>null);await raw('/auth/logout','POST');sessionRevision++;accessToken='';}

};
export async function api<T>(path:string,method='GET',body?:unknown):Promise<T>{try{return await raw<T>(path,method,body)}catch(e){if(e instanceof ApiError&&e.status===401){if(await auth.restore())return raw<T>(path,method,body);if(typeof window!=='undefined')window.dispatchEvent(new Event('devpilot:expired'));}throw e;}}
export const repoApi=(repo:string,path:string)=>'/repositories/'+repo+path;
