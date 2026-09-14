"use client";
import {useEffect,useState,useCallback} from 'react';
import {api} from '@/lib/devpilot-api';
export function useResource<T>(path:string|null,interval=0){const [data,setData]=useState<T|null>(null);const [loading,setLoading]=useState(!!path);const [error,setError]=useState('');const [revision,setRevision]=useState(0);const reload=useCallback(()=>setRevision(x=>x+1),[]);
 const [loadedPath,setLoadedPath]=useState<string|null>(null);
 useEffect(()=>{let active=true;let pending=false;if(!path)return;async function load(){if(pending)return;pending=true;try{const result=await api<T>(path!);if(active){setData(result);setError('');setLoadedPath(path);}}catch(e){if(active){setError(e instanceof Error?e.message:'Request failed');setLoadedPath(path)}}finally{pending=false;if(active)setLoading(false)}}void load();const timer=interval?setInterval(load,interval):null;return()=>{active=false;if(timer)clearInterval(timer)}},[path,revision,interval]);
 const current=loadedPath===path;
 return {data:current?data:null,loading:!!path&&(!current||loading),error:current?error:'',reload};}
