"use client";
import type {ReactNode} from 'react';
import {Select,SelectContent,SelectItem,SelectTrigger,SelectValue} from '@/components/ui/select';
import {Empty,EmptyHeader,EmptyTitle,EmptyDescription} from '@/components/ui/empty';
import {Skeleton} from '@/components/ui/skeleton';
import {Button} from '@/components/ui/button';
import {Pagination,PaginationContent,PaginationItem} from '@/components/ui/pagination';
export function Choice({value,onChange,options,label}:{value:string;onChange:(v:string)=>void;options:{value:string;label:string}[];label:string}){return <Select value={value} onValueChange={onChange}><SelectTrigger aria-label={label}><SelectValue placeholder={label}/></SelectTrigger><SelectContent>{options.map(o=><SelectItem key={o.value} value={o.value}>{o.label}</SelectItem>)}</SelectContent></Select>}
export function Badge({children}:{children:ReactNode}){return <span className={'badge badge-'+String(children).toLowerCase().replaceAll('_','-')}>{String(children).replaceAll('_',' ')}</span>}
export function EmptyState({title,detail,children}:{title:string;detail:string;children?:ReactNode}){return <Empty className="empty-state"><EmptyHeader><EmptyTitle>{title}</EmptyTitle><EmptyDescription>{detail}</EmptyDescription></EmptyHeader>{children}</Empty>}
export function Loading(){return <div className="space-y-4" aria-label="Loading" role="status"><Skeleton className="h-16 w-full"/><Skeleton className="h-40 w-full"/></div>}
export function Pager({page,total,onChange}:{page:number;total:number;onChange:(v:number)=>void}){return <Pagination className="my-5"><PaginationContent><PaginationItem><Button variant="outline" disabled={page===0} onClick={()=>onChange(page-1)}>Previous</Button></PaginationItem><PaginationItem><span className="px-3 text-sm">Page {page+1} of {Math.max(1,total)}</span></PaginationItem><PaginationItem><Button variant="outline" disabled={page+1>=total} onClick={()=>onChange(page+1)}>Next</Button></PaginationItem></PaginationContent></Pagination>}
export function Panel({title,children,action}:{title:string;children:ReactNode;action?:ReactNode}){return <section className="panel"><div className="panel-header"><h2>{title}</h2>{action}</div>{children}</section>}
export const human=(s:string)=>s.replaceAll('_',' ').toLowerCase().replace(/\b\w/g,c=>c.toUpperCase());
export const date=(s:string)=>s?new Date(s).toLocaleDateString(undefined,{month:'short',day:'numeric',year:'numeric'}):'—';
