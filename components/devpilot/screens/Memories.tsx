"use client";
import { Button } from '@/components/ui/button';
import { api,repoApi as path,type Memory,type Page,type Repo } from '@/lib/devpilot-api';
import { BrainCircuit } from 'lucide-react';
import { useState } from 'react';
import { Badge,EmptyState,Pager,date } from '../ui';
import { useResource } from '../use-resource';
import { State,useAction } from "./shared";
export function Memories({ repo }: {
    repo: Repo;
}) { const [page, setPage] = useState(0); const resource = useResource<Page<Memory>>(path(repo.id, '/memories?page=' + page)); const action = useAction(); return <><div className="info-strip"><BrainCircuit size={22}/><p>Patterns are grouped by issue type, file, and normalized title. Each distinct analysis adds at most one occurrence. Active patterns inform future reviews.</p></div><State resource={resource}>{resource.data?.content.length ? <div className="memory-grid">{resource.data.content.map(m => <article className="panel memory-card" key={m.id}><div className="flex justify-between"><Badge>{m.status}</Badge><BrainCircuit size={20}/></div><h2>{m.description}</h2><div className="memory-count"><strong>{m.occurrences}</strong><span>{m.occurrences === 1 ? 'recorded occurrence' : 'recorded occurrences'}</span></div><small>First seen {date(m.createdAt)} · Last seen {date(m.lastSeen)}</small><Button variant="outline" disabled={action.busy} onClick={() => action.run(async () => { await api(path(repo.id, '/memories/' + m.id), 'PATCH', { status: m.status === 'ACTIVE' ? 'ARCHIVED' : 'ACTIVE' }); resource.reload(); }, 'Memory updated')}>{m.status === 'ACTIVE' ? 'Archive pattern' : 'Reactivate pattern'}</Button></article>)}</div> : <EmptyState title="Every review is a chance to learn" detail="Findings become persistent memory. Repeated patterns gain occurrence counts over time."/>}<Pager page={page} total={resource.data?.totalPages ?? 0} onChange={setPage}/></State></>; }
