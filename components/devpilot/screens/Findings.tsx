"use client";
import { Button } from '@/components/ui/button';
import { api,repoApi as path,type Finding,type Page,type Repo } from '@/lib/devpilot-api';
import { Plus } from 'lucide-react';
import { useState } from 'react';
import { EmptyState,Pager } from '../ui';
import { useResource } from '../use-resource';
import { FindingCard,State,useAction } from "./shared";
export function Findings({ repo }: {
    repo: Repo;
}) { const [page, setPage] = useState(0); const resource = useResource<Page<Finding>>(path(repo.id, '/findings?page=' + page)); const action = useAction(); return <State resource={resource}>{resource.data?.content.length ? resource.data.content.map(f => <FindingCard key={f.id} f={f} action={<Button variant="outline" size="sm" disabled={action.busy} onClick={() => action.run(() => api(path(repo.id, '/technical-debt'), 'POST', { title: f.title, description: f.description, category: f.type, severity: f.severity, estimatedEffort: 'Unestimated', sourceFindingId: f.id }), 'Finding added to technical debt')}><Plus size={14}/>Track as debt</Button>}/>) : <EmptyState title="No findings yet" detail="Completed analyses appear here, with evidence and recommendations."/>}<Pager page={page} total={resource.data?.totalPages ?? 0} onChange={setPage}/></State>; }
