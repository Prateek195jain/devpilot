"use client";
import { Button } from '@/components/ui/button';
import { type Finding } from '@/lib/devpilot-api';
import { CheckCircle2 } from 'lucide-react';
import { useState,type ReactNode } from 'react';
import { toast } from 'sonner';
import { Badge,EmptyState,Loading,human } from '../ui';
const options = (values: string[]) => values.map(value => ({ value, label: human(value) }));

const severities = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'];

const statuses = ['OPEN', 'IN_PROGRESS', 'RESOLVED', 'IGNORED'];

function ErrorBox({ message, retry }: {
    message: string;
    retry: () => void;
}) { return <div role="alert" className="error-banner">{message}<Button variant="link" onClick={retry}>Retry</Button></div>; }

function State({ resource, children, emptyTitle = 'Nothing here yet', emptyDetail = 'New activity will appear here.' }: {
    resource: {
        loading: boolean;
        error: string;
        reload: () => void;
        data: unknown;
    };
    children: ReactNode;
    emptyTitle?: string;
    emptyDetail?: string;
}) { if (resource.loading)
    return <Loading />; if (resource.error)
    return <ErrorBox message={resource.error} retry={resource.reload}/>; if (!resource.data)
    return <EmptyState title={emptyTitle} detail={emptyDetail}/>; return <>{children}</>; }

function useAction() { const [busy, setBusy] = useState(false); async function run(fn: () => Promise<unknown>, success?: string) { setBusy(true); try {
    await fn();
    if (success)
        toast.success(success);
    return true;
}
catch (e) {
    toast.error((e as Error).message);
    return false;
}
finally {
    setBusy(false);
} } return { busy, run }; }

function FindingCard({ f, action }: {
    f: Finding;
    action?: ReactNode;
}) { return <article className="finding-card"><div className="flex flex-wrap gap-2 items-center"><Badge>{f.severity}</Badge><span className="eyebrow">{human(f.type)}</span><span className="ml-auto muted text-xs">{Math.round(f.confidence * 100)}% confidence · {human(f.source)}</span></div><h3>{f.title}</h3><code>{f.file}{f.line ? ':' + f.line : ''}</code><p>{f.description}</p><div className="recommendation"><CheckCircle2 size={16}/><p>{f.recommendation}</p></div>{action}</article>; }
export { ErrorBox,FindingCard,State,options,severities,statuses,useAction };
