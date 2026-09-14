"use client";
import { Button } from '@/components/ui/button';
import { Dialog,DialogContent,DialogDescription,DialogHeader,DialogTitle,DialogTrigger } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { api,type Page,type Repo } from '@/lib/devpilot-api';
import { ArrowRight,FolderGit2,Plus } from 'lucide-react';
import { useState } from 'react';
import { EmptyState,Pager,date } from '../ui';
import { useResource } from '../use-resource';
import { State,useAction } from "./shared";
export function Repositories({ select, onChange }: {
    select: (id: string) => void;
    onChange: () => void;
}) { const [page, setPage] = useState(0); const [open, setOpen] = useState(false); const [query, setQuery] = useState(''); const resource = useResource<Page<Repo>>('/repositories?page=' + page); const github = useResource<{
    id: number;
    fullName: string;
    description: string;
    language: string;
}[]>(open ? '/github/repositories' : null); const action = useAction(); async function importRepo(fullName: string) { await action.run(async () => { const r = await api<Repo>('/repositories', 'POST', { fullName }); resource.reload(); onChange(); setOpen(false); select(r.id); }, 'Repository imported'); } ; return <><div className="toolbar"><span>{resource.data?.totalElements ?? 0} connected repositories</span><Dialog open={open} onOpenChange={setOpen}><DialogTrigger asChild><Button><Plus size={16}/>Import repository</Button></DialogTrigger><DialogContent><DialogHeader><DialogTitle>Bring your code into focus</DialogTitle><DialogDescription>Import a repository your connected GitHub account can access.</DialogDescription></DialogHeader><form className="flex gap-2" onSubmit={e => { e.preventDefault(); void importRepo(query); }}><Input aria-label="Repository full name" placeholder="owner/repository" value={query} onChange={e => setQuery(e.target.value)} required pattern="[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"/><Button disabled={action.busy}>Import</Button></form><State resource={github}><div className="import-list">{github.data?.map(r => <div className="review-row" key={r.id}><FolderGit2 size={16}/><div><strong>{r.fullName}</strong><small>{r.language || 'Repository'}</small></div><Button size="sm" variant="outline" disabled={action.busy} onClick={() => importRepo(r.fullName)}>Import</Button></div>)}</div><small>Showing the first 30 GitHub repositories. You can also import any accessible repository by full name above.</small></State></DialogContent></Dialog></div><State resource={resource}>{resource.data?.content.length ? <div className="repo-grid">{resource.data.content.map(r => <button key={r.id} className="repo-card" onClick={() => select(r.id)}><div className="flex justify-between"><FolderGit2 size={24}/><ArrowRight size={18}/></div><h2>{r.fullName}</h2><p>{r.description || 'No description provided.'}</p><div><span className="language-dot"/>{r.language || 'Unknown language'}<span className="muted"> · {r.defaultBranch}</span></div><small>Synced {date(r.syncedAt)}</small></button>)}</div> : <EmptyState title="Make room for your code" detail="Connect GitHub in Settings, then import your first repository."/>}<Pager page={page} total={resource.data?.totalPages ?? 0} onChange={setPage}/></State></>; }
