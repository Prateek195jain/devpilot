import type { Repo } from '@/lib/devpilot-api';
export function IndexingStatus({ repo }: { repo: Repo }) {
 let message:string;
 switch(repo.indexingStatus) {
  case 'INDEXING': message='Indexing repository... You can keep using DevPilot.'; break;
  case 'READY': message='Repository knowledge ready'; break;
  case 'FAILED': message=repo.latestIndexedCommitSha?'Index refresh failed. Using previous repository knowledge.':'Repository indexing failed. Retry Sync.'; break;
  default: message=repo.latestIndexedCommitSha?'Repository knowledge ready':'Repository knowledge has not been indexed yet.';
 }
 return <div role="status" className="text-sm text-muted-foreground py-2">
  <span>{message}</span>
  {repo.indexingStatus==='FAILED' && repo.lastIndexingErrorCategory==='RATE_LIMIT' &&
   <p>Repository indexing is temporarily rate-limited. DevPilot will preserve the previous index; retry shortly.</p>}
 </div>;
}
