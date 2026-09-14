"use client";
import { Button } from '@/components/ui/button';
import { Dialog,DialogContent,DialogDescription,DialogHeader,DialogTitle } from '@/components/ui/dialog';
import { Table,TableBody,TableCell,TableHead,TableHeader,TableRow } from '@/components/ui/table';
import { Tabs,TabsContent,TabsList,TabsTrigger } from '@/components/ui/tabs';
import { api,repoApi as path,type Change,type Repo } from '@/lib/devpilot-api';
import { ArrowRight,FileCode2,FolderGit2,RefreshCw } from 'lucide-react';
import { useState } from 'react';
import { Badge,EmptyState,Panel,date } from '../ui';
import { useResource } from '../use-resource';
import { State,useAction } from "./shared";
export function RepositoryDetails({ repo, changed }: {
    repo: Repo;
    changed: () => void;
}) { const [kind, setKind] = useState('PR'); const [page, setPage] = useState(1); const resource = useResource<Change[]>(kind === 'BRANCHES' ? null : path(repo.id, '/changes?kind=' + kind + '&page=' + page)); const branches = useResource<string[]>(path(repo.id, '/branches')); const [diff, setDiff] = useState<{
    diff: string;
    truncated: boolean;
    files: {
        filename: string;
        additions: number;
        deletions: number;
    }[];
} | null>(null); const [diffOpen, setDiffOpen] = useState(false); const action = useAction(); return <><div className="repo-detail-banner"><FolderGit2 size={36}/><div><h2>{repo.fullName}</h2><p>{repo.description || 'Connected GitHub repository'}</p><small>{repo.language} · Default branch: {repo.defaultBranch} · Synced {date(repo.syncedAt)}</small></div><Button variant="outline" disabled={action.busy} onClick={() => action.run(async () => { await api(path(repo.id, '/sync'), 'POST'); changed(); resource.reload(); }, 'Repository refreshed')}><RefreshCw size={15}/>Sync</Button></div><Tabs value={kind} onValueChange={v => { setKind(v); setPage(1); }}><TabsList><TabsTrigger value="PR">Pull requests</TabsTrigger><TabsTrigger value="COMMIT">Commits</TabsTrigger><TabsTrigger value="BRANCHES">Branches</TabsTrigger></TabsList>{kind === 'BRANCHES' ? <TabsContent value="BRANCHES"><State resource={branches}><Panel title="Repository branches">{branches.data?.map(b => <div className="review-row" key={b}><FileCode2 size={16}/><code>{b}</code></div>)}</Panel></State></TabsContent> : <TabsContent value={kind}><State resource={resource}>{resource.data?.length ? <div className="table-panel"><Table><TableHeader><TableRow><TableHead>Change</TableHead><TableHead>Author</TableHead><TableHead>State</TableHead><TableHead>Review</TableHead></TableRow></TableHeader><TableBody>{resource.data.map(c => <TableRow key={c.ref}><TableCell><strong>{c.title.split('\n')[0]}</strong><small className="block mono">{kind === 'PR' ? '#' + c.ref : c.ref.slice(0, 10)}</small></TableCell><TableCell>{c.author}</TableCell><TableCell><Badge>{c.state}</Badge></TableCell><TableCell><div className="flex gap-2"><Button variant="outline" size="sm" disabled={action.busy} onClick={() => action.run(async () => { setDiff(await api(path(repo.id, '/diff?kind=' + kind + '&ref=' + encodeURIComponent(c.ref)))); setDiffOpen(true); })}>Diff</Button><Button size="sm" disabled={action.busy} onClick={() => action.run(() => api(path(repo.id, '/analyses'), 'POST', { kind, ref: c.ref }), 'Analysis queued. Follow progress in Code analysis.')}>Analyze<ArrowRight size={13}/></Button></div></TableCell></TableRow>)}</TableBody></Table></div> : <EmptyState title="No changes on this page" detail="Push a commit or open a pull request on GitHub."/>}<div className="flex justify-center gap-3 my-5"><Button variant="outline" disabled={page === 1} onClick={() => setPage(page - 1)}>Previous</Button><span>Page {page}</span><Button variant="outline" disabled={(resource.data?.length ?? 0) < 30} onClick={() => setPage(page + 1)}>Next</Button></div></State></TabsContent>}</Tabs><Dialog open={diffOpen} onOpenChange={setDiffOpen}><DialogContent className="sm:max-w-4xl"><DialogHeader><DialogTitle>Changed files</DialogTitle><DialogDescription>{diff?.truncated ? 'Partial coverage: some content was omitted.' : 'Diff retrieved from GitHub.'}</DialogDescription></DialogHeader><div className="diff-files">{diff?.files.map(f => <div key={f.filename}><code>{f.filename}</code><span>+{f.additions} / −{f.deletions}</span></div>)}</div><pre className="diff-view">{diff?.diff || 'No textual diff available.'}</pre></DialogContent></Dialog></>; }
