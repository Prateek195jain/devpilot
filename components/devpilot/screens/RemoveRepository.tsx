"use client";
import { useRef,useState } from 'react';
import { AlertDialog,AlertDialogAction,AlertDialogCancel,AlertDialogContent,AlertDialogDescription,AlertDialogFooter,AlertDialogHeader,AlertDialogTitle,AlertDialogTrigger } from '@/components/ui/alert-dialog';
import { Button } from '@/components/ui/button';
import { api,repoApi,type Repo } from '@/lib/devpilot-api';
import { useAction } from './shared';

export function RemoveRepository({ repo, removed, disabled = false }: {
    repo: Repo;
    removed: () => void;
    disabled?: boolean;
}) {
    const [open,setOpen] = useState(false);
    const pending = useRef(false);
    const action = useAction();
    async function remove() {
        if (pending.current || disabled) return;
        pending.current = true;
        try {
            const success = await action.run(() => api(repoApi(repo.id,''),'DELETE'),
                'Repository removed from DevPilot. GitHub repository unchanged.');
            if (success) { setOpen(false); removed(); }
        } finally { pending.current = false; }
    }
    return <AlertDialog open={open} onOpenChange={value => { if (!pending.current) setOpen(value); }}>
        <AlertDialogTrigger asChild><Button variant="outline" disabled={disabled || action.busy}>Remove repository</Button></AlertDialogTrigger>
        <AlertDialogContent>
            <AlertDialogHeader>
                <AlertDialogTitle>Remove {repo.fullName} from DevPilot?</AlertDialogTitle>
                <AlertDialogDescription>This removes the repository and its DevPilot analysis data. The GitHub repository will not be changed.</AlertDialogDescription>
            </AlertDialogHeader>
            <AlertDialogFooter>
                <AlertDialogCancel disabled={action.busy}>Cancel</AlertDialogCancel>
                <AlertDialogAction disabled={action.busy} onClick={event => { event.preventDefault(); void remove(); }}>
                    {action.busy ? 'Removing…' : 'Remove repository'}
                </AlertDialogAction>
            </AlertDialogFooter>
        </AlertDialogContent>
    </AlertDialog>;
}
